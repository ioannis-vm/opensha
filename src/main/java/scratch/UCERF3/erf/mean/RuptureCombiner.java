package scratch.UCERF3.erf.mean;

import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.stat.StatUtils;
import org.dom4j.DocumentException;
import org.opensha.commons.data.function.ArbitrarilyDiscretizedFunc;
import org.opensha.commons.data.function.DiscretizedFunc;
import org.opensha.commons.data.function.LightFixedXFunc;
import org.opensha.commons.util.FaultUtils;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.ProbEqkRupture;
import org.opensha.sha.earthquake.ProbEqkSource;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.RupMFDsModule;
import org.opensha.sha.earthquake.param.IncludeBackgroundOption;
import org.opensha.sha.earthquake.param.IncludeBackgroundParam;
import org.opensha.sha.faultSurface.FaultSection;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.erf.FaultSystemSolutionERF;
import scratch.UCERF3.inversion.InversionFaultSystemRupSet;
import scratch.UCERF3.inversion.InversionFaultSystemRupSetFactory;
import scratch.UCERF3.utils.U3FaultSystemIO;
import scratch.UCERF3.utils.MatrixIO;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.common.primitives.Ints;

/**
 * This class handles the various ways to combine ruptures from a true mean UCERF3 solution
 * (generated by TrueMeanBuilder) into a smaller but approximately identical averaged solution.
 * 
 * @author kevin
 *
 */
public class RuptureCombiner {
	
	/**
	 * Returns a FaultSystemSolution that has been optimized by combining multiple instances of the same
	 * subsection with slightly different aseismicity values and or ruptures with different rakes.
	 * 
	 * Will only work with solutions generated by TrueMeanBuilder.
	 * 
	 * @param meanSol
	 * @param upperDepthTol upper depth tolerance with wich to average (if a subsection's upper depth is
	 * outside of this tolerance from the mean, it will not be averaged)
	 * @param useAvgUpperDepth if true the average upper depth will be used, else the shallowest will be used.
	 * Note that if true, a regular average is used (not weighted by, for example, nucleation rate).
	 * @param combineRakes if true, rakes will be merged for otherwise identical ruptures
	 * @param rake basis map from hashset of subsection names to rakes, or null to use weighted average rakes
	 * @return
	 */
	public static FaultSystemSolution getCombinedSolution(FaultSystemSolution meanSol,
			double upperDepthTol, boolean useAvgUpperDepth,
			boolean combineRakes, Map<Set<String>, Double> rakesBasis) {
		
		final boolean D = true;
		
		FaultSystemRupSet origRupSet = meanSol.getRupSet();
		
		// old sect => new sect (if upper depth combining, else null)
		Map<Integer, Integer> sectIndexMapping = null;
		
		List<? extends FaultSection> combinedSects;
		List<List<Integer>> mappedSectionsForRups;
		if (upperDepthTol <= 0) {
			if (D) System.out.println("Skipping upper depth combine");
			combinedSects = origRupSet.getFaultSectionDataList();
			mappedSectionsForRups = origRupSet.getSectionIndicesForAllRups();
		} else {
			if (D) System.out.println("Combining upper depths with tol="+upperDepthTol);
			// common name => sects that share that name
			Map<String, List<FaultSection>> origSectsMap = Maps.newHashMap();
			for (FaultSection sect : origRupSet.getFaultSectionDataList()) {
				String name = getStrippedName(sect.getName());
				List<FaultSection> sects = origSectsMap.get(name);
				if (sects == null) {
					sects = Lists.newArrayList();
					origSectsMap.put(name, sects);
				}
				sects.add(sect);
			}
			
			System.out.println("Found "+origSectsMap.size()+"/"+origRupSet.getNumSections()+" unique sections");
			
			// old sect => new sect
			Map<FaultSection, FaultSection> sectMapping = Maps.newHashMap();
			for (String name : origSectsMap.keySet()) {
				List<FaultSection> sects = origSectsMap.get(name);
				int origNum = sects.size();
				List<FaultSection> newCombinedSects = Lists.newArrayList();
				Collections.sort(sects, upperDepthCompare);
				while (sects.size() > 1) {
					// find largest available grouping that is within tolerance
					List<Integer> indexes = null;
					for (int i=0; i<sects.size()-1; i++) {
						List<Integer> myIndexes = Lists.newArrayList();
						myIndexes.add(i);
						if (indexes == null)
							indexes = myIndexes;
						for (int j=i+1; j<sects.size(); j++) {
							myIndexes.add(j);
							if (areAllUpperDepthsWithinTolOfMean(sects, myIndexes, upperDepthTol)) {
								if (myIndexes.size() > indexes.size())
									indexes = myIndexes;
							} else {
								// the last one broke it, remove
								myIndexes.remove(myIndexes.size()-1);
								break;
							}
						}
					}
					List<FaultSection> sectsToCombine = Lists.newArrayList();
					
					if (upperDepthTol > 30 && indexes.size() != sects.size()) {
						System.out.println("Weird...depth huge but not all within!");
					}
					
					// order from largest to smallest
					Collections.reverse(indexes);
					
					for (int i : indexes) {
						sectsToCombine.add(sects.remove(i));
					}
					
					if (sectsToCombine.size() == 1) {
						// simple case, just maps to itself
						FaultSection newSect = sectsToCombine.get(0).clone();
						sectMapping.put(sectsToCombine.get(0), newSect);
						newCombinedSects.add(newSect);
					} else {
						// combine them
						FaultSection combined = sectsToCombine.get(0).clone();
						double[] aseisVals = new double[sectsToCombine.size()];
						for (int i=0; i<sectsToCombine.size(); i++) {
							FaultSection sect = sectsToCombine.get(i);
							aseisVals[i] = sect.getAseismicSlipFactor();
							sectMapping.put(sect, combined);
						}
						double myAseis;
						if (useAvgUpperDepth)
							myAseis = StatUtils.mean(aseisVals);
						else
							myAseis = StatUtils.max(aseisVals);
						Preconditions.checkState(!Double.isNaN(myAseis) && !Double.isInfinite(myAseis));
						combined.setAseismicSlipFactor(myAseis);
						newCombinedSects.add(combined);
					}
				}
				if (!sects.isEmpty()) {
					// simple case, just maps to itself
					FaultSection newSect = sects.get(0).clone();
					sectMapping.put(sects.get(0), newSect);
					newCombinedSects.add(newSect);
				}
				// now set names
				Preconditions.checkState(newCombinedSects.size() > 0);
				Preconditions.checkState(newCombinedSects.size() <= origNum);
				if (newCombinedSects.size() == 1)
					newCombinedSects.get(0).setSectionName(name);
				else
					for (int i=0; i<newCombinedSects.size(); i++)
						newCombinedSects.get(i).setSectionName(name+" (instance "+i+")");
				
			}
			Preconditions.checkState(sectMapping.size() == origRupSet.getNumSections());
			// create list and take care of IDs
			// pass through a HashSet to remove duplicates
			combinedSects = Lists.newArrayList(new HashSet<FaultSection>(sectMapping.values()));
			for (int i=0; i<combinedSects.size(); i++)
				combinedSects.get(i).setSectionId(i);
			
			// create index mapping for later
			// old sect -> new sect
			sectIndexMapping = Maps.newHashMap();
			for (FaultSection key : sectMapping.keySet())
				sectIndexMapping.put(key.getSectionId(), sectMapping.get(key).getSectionId());
			
			// now fix IDs for each rupture
			List<? extends FaultSection> origSects = origRupSet.getFaultSectionDataList();
			Map<Integer, Integer> sectIDsMap = Maps.newHashMap();
			for (FaultSection sect : origSects) {
				FaultSection mappedSect = sectMapping.get(sect);
				Preconditions.checkNotNull(mappedSect);
				sectIDsMap.put(sect.getSectionId(), mappedSect.getSectionId());
			}
			List<List<Integer>> sectionForRupsOrig = origRupSet.getSectionIndicesForAllRups();
			mappedSectionsForRups = Lists.newArrayList();
			for (int r=0; r<sectionForRupsOrig.size(); r++) {
				List<Integer> origIDs = sectionForRupsOrig.get(r);
				List<Integer> newIDs = Lists.newArrayList();
				for (int s : origIDs)
					newIDs.add(sectIDsMap.get(s));
				mappedSectionsForRups.add(newIDs);
			}
		}
		
		if (D) System.out.println("Precombine we have "+mappedSectionsForRups.size()+" rups");
		
		// now combine identical ruptures
		Table<IntHashSet, Double, List<Integer>> combinedRupsMap = HashBasedTable.create();
		// TODO optimize this loop, it is the slowest part of the method
		if (D) System.out.println("Finding identical rups to combine");
		for (int r=0; r<mappedSectionsForRups.size(); r++) {
			List<Integer> sectIDsList = mappedSectionsForRups.get(r);
			IntHashSet sectIDs = new IntHashSet(sectIDsList);
			Preconditions.checkState(sectIDs.size() == sectIDsList.size(), "Duplicate sect IDs in rup!");
			Double rake = origRupSet.getAveRakeForRup(r);
			List<Integer> matches = combinedRupsMap.get(sectIDs, rake);
			if (matches == null) {
				matches = Lists.newArrayList();
				combinedRupsMap.put(sectIDs, rake, matches);
			}
			matches.add(r);
		}
		if (D) System.out.println("Done finding identical rups (now have "+combinedRupsMap.size()+" rups pre rakes)");
		if (combineRakes == false && upperDepthTol <= 0 && D) {
			if (D) System.out.println("Doing none-changed sanity check!");
			// nothing should be changed, sanity check
			for (List<Integer> matches : combinedRupsMap.values()) {
				if (matches.size() > 1) {
					System.out.println("Found a match, there shouldn't be one!");
					for (int rupID : matches)
						System.out.println("\trup "+rupID+": "+Joiner.on(",").join(mappedSectionsForRups.get(rupID))
								+"\trake="+origRupSet.getAveRakeForRup(rupID)
								+"\tmag="+origRupSet.getMagForRup(rupID)
								+"\trate="+meanSol.getRateForRup(rupID)
								+"\tarea="+origRupSet.getAreaForRup(rupID));
					// check with strings
					HashSet<HashSet<String>> subSectNamesSet = new HashSet<HashSet<String>>();
					for (int rupID : matches) {
						HashSet<String> subSectNames = new HashSet<String>();
						for (int s : mappedSectionsForRups.get(rupID))
							subSectNames.add(combinedSects.get(s).getName());
						if (subSectNamesSet.isEmpty())
							subSectNamesSet.add(subSectNames);
						else if (!subSectNamesSet.contains(subSectNames)) {
							System.out.println("Weird, string sets not equal!");
							List<String> alreadyIn = Lists.newArrayList(subSectNames.iterator().next());
							List<String> newNames = Lists.newArrayList(subSectNames);
							System.out.print("OLD: "+Joiner.on(',').join(alreadyIn));
							System.out.print("NEW: "+Joiner.on(',').join(newNames));
							System.exit(0);
						}
					}
				}
			}
			if (D) System.out.println("Done with sanity check");
		}
		
		if (combineRakes) {
			if (D) System.out.println("Combining rakes");
			Table<IntHashSet, Double, List<Integer>> rakeCombinedRupsMap = HashBasedTable.create();
			Map<IntHashSet, Map<Double, List<Integer>>> rowMap = combinedRupsMap.rowMap();
			for (IntHashSet sectIDs : rowMap.keySet()) {
				Map<Double, List<Integer>> colValMap = rowMap.get(sectIDs);
				double newRake;
				if (rakesBasis == null) {
					// mean rake
					List<Double> rakesList = Lists.newArrayList(colValMap.keySet());
					List<Double> ratesList = Lists.newArrayList();
					for (int i=0; i<rakesList.size(); i++) {
						double rate = 0;
						for (int r : colValMap.get(rakesList.get(i))) {
							rate += meanSol.getRateForRup(r);
						}
						ratesList.add(rate);
					}
					newRake = FaultUtils.getInRakeRange(FaultUtils.getScaledAngleAverage(ratesList, rakesList));
				} else {
					// use supplied rake
					HashSet<String> subsectNames = new HashSet<String>();
					for (int s : sectIDs)
						subsectNames.add(getStrippedName(combinedSects.get(s).getName()));
					Double suppliedRake = rakesBasis.get(subsectNames);
					Preconditions.checkNotNull(suppliedRake, "Rake not found!");
					newRake = suppliedRake;
				}
				List<Integer> rupIDsList = Lists.newArrayList();
				for (List<Integer> rupIDs : colValMap.values())
					rupIDsList.addAll(rupIDs);
				rakeCombinedRupsMap.put(sectIDs, newRake, rupIDsList);
			}
			combinedRupsMap = rakeCombinedRupsMap;
			if (D) System.out.println("Done combining rakes");
		}
		if (D) System.out.println("Building combined sol with "+combinedRupsMap.size()+" rups");
		List<List<Integer>> combinedMappedSectionsForRups = Lists.newArrayList();
		int numCombinedRups = combinedRupsMap.size();
		double[] mags = new double[numCombinedRups];
		double[] rakes = new double[numCombinedRups];
		double[] rupAreas = new double[numCombinedRups];
		double[] rates = new double[numCombinedRups];
		RupMFDsModule meanMFDsModule = meanSol.requireModule(RupMFDsModule.class);
		DiscretizedFunc[] mfds = new DiscretizedFunc[numCombinedRups];
		int runningRupIndex = 0;
		for (Cell<IntHashSet, Double, List<Integer>> cell : combinedRupsMap.cellSet()) {
			double rake = cell.getColumnKey();
			List<Integer> combinedRups = cell.getValue();
			
			DiscretizedFunc mfd;
			double mag, area, rate;
			if (combinedRups.size() == 1) {
				// simple case
				int myRupIndex = combinedRups.get(0);
				mag = origRupSet.getMagForRup(myRupIndex);
				area = origRupSet.getAreaForRup(myRupIndex);
				rate = meanSol.getRateForRup(myRupIndex);
				mfd = meanMFDsModule.getRuptureMFD(myRupIndex);
			} else {
				double[] myRates = new double[combinedRups.size()];
				double[] myAreas = new double[combinedRups.size()];
				DiscretizedFunc[] funcs = new DiscretizedFunc[combinedRups.size()];
				for (int i=0; i<combinedRups.size(); i++) {
					int r = combinedRups.get(i);
					myRates[i] = meanSol.getRateForRup(r);
					myAreas[i] = origRupSet.getAreaForRup(r);
					DiscretizedFunc func = meanMFDsModule.getRuptureMFD(r);
					if (func == null) {
						double[] xVals = { origRupSet.getMagForRup(r) };
						double[] yVals = { meanSol.getRateForRup(r) };
						func = new LightFixedXFunc(xVals, yVals);
					}
					funcs[i] = func;
				}
				area = calcScaledAverage(myRates, myAreas);
				rate = StatUtils.sum(myRates);
				DiscretizedFunc totFunc = new ArbitrarilyDiscretizedFunc();
				for (DiscretizedFunc func : funcs) {
					for (Point2D pt : func) {
						if (pt.getY() == 0)
							continue;
						int ind = totFunc.getIndex(pt);
						if (ind < 0)
							totFunc.set(pt);
						else
							totFunc.set(ind, pt.getY()+totFunc.getY(ind));
					}
				}
				double[] allMags = new double[totFunc.size()];
				double[] allRates = new double[totFunc.size()];
				for (int i=0; i<totFunc.size(); i++) {
					allMags[i] = totFunc.getX(i);
					allRates[i] = totFunc.getY(i);
				}
				Preconditions.checkState((float)StatUtils.sum(allRates) == (float)rate);
				mag = calcScaledAverage(allRates, allMags);
				mfd = new LightFixedXFunc(totFunc);
			}
			
			mags[runningRupIndex] = mag;
			rates[runningRupIndex] = rate;
			rupAreas[runningRupIndex] = area;
			mfds[runningRupIndex] = mfd;
			rakes[runningRupIndex] = rake;
			
//			List<Integer> sects = Lists.newArrayList(cell.getRowKey());
			// do it this way to ensure correct ordering
			List<Integer> origSects = origRupSet.getSectionsIndicesForRup(combinedRups.get(0));
			List<Integer> sects;
			if (sectIndexMapping == null) {
				sects = origSects;
			} else {
				// need to map
				sects = Lists.newArrayList();
				for (int id : origSects)
					sects.add(sectIndexMapping.get(id));
				sects = MatrixIO.getMemoryEfficientIntArray(sects);
			}
			// pass it through an array to use efficient 
			combinedMappedSectionsForRups.add(sects);
			
			runningRupIndex++;
		}
		String info = "Combined Solution";
		
		FaultSystemRupSet rupSet = new FaultSystemRupSet(combinedSects, combinedMappedSectionsForRups,
				mags, rakes, rupAreas, null);
		FaultSystemSolution sol = new FaultSystemSolution(rupSet, rates);
		sol.setGridSourceProvider(meanSol.getGridSourceProvider());
		sol.addModule(new RupMFDsModule(mfds));
		
		// now check total rates
		double origTotRate = 0;
		for (double rate : meanSol.getRateForAllRups())
			origTotRate += rate;
		double newTotRate = 0;
		for (double rate : sol.getRateForAllRups())
			newTotRate += rate;
		
		Preconditions.checkState((float)origTotRate == (float)newTotRate, "rates don't match! "+origTotRate+" != "+newTotRate);
		
		return sol;
	}
	
	/**
	 * Memory efficient int hash set
	 * @author kevin
	 *
	 */
	public static class IntHashSet extends AbstractSet<Integer> {
		
		private int[] vals;
		
		public IntHashSet(Collection<Integer> vals) {
			this(Ints.toArray(vals), false);
		}
		
		public IntHashSet(int[] vals, boolean alreadySorted) {
			if (!alreadySorted)
				Arrays.sort(vals);
			
			// remove duplicates
			List<Integer> dupIndexes = Lists.newArrayList();
			for (int i=1; i<vals.length; i++) {
				if (vals[i] == vals[i-1])
					dupIndexes.add(i);
			}
			if (!dupIndexes.isEmpty()) {
				int[] newvals = new int[vals.length-dupIndexes.size()];
				int newIndex = 0;
				int curDup = dupIndexes.get(0);
				for (int oldIndex=0; oldIndex<vals.length; oldIndex++) {
					if (oldIndex == curDup) {
						// we're at a duplicate
						dupIndexes.remove(0);
						if (dupIndexes.isEmpty())
							curDup = -1;
						else
							curDup = dupIndexes.get(0);
						continue;
					}
					newvals[newIndex++] = vals[oldIndex];
				}
				Preconditions.checkState(newIndex == newvals.length);
				Preconditions.checkState(dupIndexes.isEmpty());
				vals = newvals;
			}
			
			
			this.vals = vals;
		}

		@Override
		public Iterator<Integer> iterator() {
			return new Iterator<Integer>() {
				
				private int index = 0;

				@Override
				public boolean hasNext() {
					return index < vals.length;
				}

				@Override
				public Integer next() {
					return vals[index++];
				}

				@Override
				public void remove() {
					throw new UnsupportedOperationException("Not supported by this iterator");
				}
			};
		}

		@Override
		public int size() {
			return vals.length;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + Arrays.hashCode(vals);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			// if both IntHashSets we can do this one
			if (getClass() == obj.getClass()) {
				IntHashSet other = (IntHashSet) obj;
				if (!Arrays.equals(vals, other.vals))
					return false;
			} else if (!super.equals(obj)) {
				return false;
			}
			
			return true;
		}
		
	}
	
	private static boolean areAllUpperDepthsWithinTolOfMean(
			List<FaultSection> fsd, List<Integer> indexes, double upperDepthTol) {
		double mean = 0d;
		for (int i : indexes)
			mean += fsd.get(i).getReducedAveUpperDepth();
		mean /= (double)indexes.size();
//		System.out.println("Checking mean of "+mean+" +/- "+upperDepthTol+" for "+Joiner.on(",").join(indexes));
		for (int i : indexes)
			if (Math.abs(mean - fsd.get(i).getReducedAveUpperDepth()) > upperDepthTol) {
//				System.out.println(i+" fails with depth: "+fsd.get(i).getReducedAveUpperDepth());
				return false;
			}
//		System.out.println("All pass!");
		return true;
	}
	
	private static boolean arePointsWithinTolOfMean(
			List<Point2D> points, List<Integer> indexes, double tol) {
		double mean = 0d;
		for (int i : indexes)
			mean += points.get(i).getX();
		mean /= (double)indexes.size();
//		System.out.println("Checking mean of "+mean+" +/- "+upperDepthTol+" for "+Joiner.on(",").join(indexes));
		for (int i : indexes)
			if (Math.abs(mean - points.get(i).getX()) > tol) {
//				System.out.println(i+" fails with depth: "+fsd.get(i).getReducedAveUpperDepth());
				return false;
			}
//		System.out.println("All pass!");
		return true;
	}
	
	private static final Comparator<FaultSection> upperDepthCompare = new Comparator<FaultSection>() {
		
		@Override
		public int compare(FaultSection o1, FaultSection o2) {
			return Double.compare(o1.getReducedAveUpperDepth(), o2.getReducedAveUpperDepth());
		}
	};
	
	public static double calcScaledAverage(double[] scalars, double[] vals) {
		double totScale = 0d;
		for (double scale : scalars)
			totScale += scale;
		
		double scaledAvg = 0;
		for (int i=0; i<scalars.length; i++) {
			double relative = scalars[i] / totScale;
			scaledAvg += relative * vals[i];
		}

		return scaledAvg;
	}
	
	public static String getStrippedName(String name) {
		if (name.contains("(instance "))
			name = name.substring(0, name.indexOf("(instance "));
		return name.trim();
	}
	
	public static Map<Set<String>, Double> loadRakeBasis(DeformationModels dm) {
		FaultModels[] fms = {FaultModels.FM3_1, FaultModels.FM3_2};
		
		Map<Set<String>, Double> rakeBasis = Maps.newHashMap();
		
		for (FaultModels fm : fms) {
			InversionFaultSystemRupSet rupSet = InversionFaultSystemRupSetFactory.forBranch(fm, dm);
			for (int rupIndex=0; rupIndex<rupSet.getNumRuptures(); rupIndex++) {
				HashSet<String> sectNames = new HashSet<String>();
				for (FaultSection sect : rupSet.getFaultSectionDataForRupture(rupIndex))
					sectNames.add(sect.getName());
				if (!rakeBasis.containsKey(sectNames))
					rakeBasis.put(sectNames, rupSet.getAveRakeForRup(rupIndex));
			}
		}
		
		return rakeBasis;
	}
	
	/**
	 * This averages magnitudes within a tolerance in the given MFD list. This will reduce
	 * the ERF rupture count.
	 * 
	 * @param magTol
	 * @param origMFDs
	 * @return
	 */
	public static DiscretizedFunc[] combineMFDs(double magTol, DiscretizedFunc[] origMFDs) {
		if (magTol <= 0)
			return origMFDs;
		
		DiscretizedFunc[] combinedMFDs = new DiscretizedFunc[origMFDs.length];
		
		for (int r=0; r<origMFDs.length; r++) {
			DiscretizedFunc origMFD = origMFDs[r];
			if (origMFD.size() <= 1) {
				combinedMFDs[r] = origMFD;
				continue;
			}
			
			List<Point2D> pts = Lists.newArrayList(origMFD);
			
			DiscretizedFunc newMFD = new ArbitrarilyDiscretizedFunc();
			
			while (pts.size() > 1) {
				// find largest available grouping that is within tolerance
				List<Integer> indexes = null;
				for (int i=0; i<pts.size()-1; i++) {
					List<Integer> myIndexes = Lists.newArrayList();
					myIndexes.add(i);
					if (indexes == null)
						indexes = myIndexes;
					for (int j=i+1; j<pts.size(); j++) {
						myIndexes.add(j);
						if (arePointsWithinTolOfMean(pts, myIndexes, magTol)) {
							if (myIndexes.size() > indexes.size())
								indexes = myIndexes;
						} else {
							// the last one broke it, remove
							myIndexes.remove(myIndexes.size()-1);
							break;
						}
					}
				}
				List<Point2D> ptsToCombine = Lists.newArrayList();
				
				if (magTol > 5 && indexes.size() != pts.size()) {
					System.out.println("Weird...depth huge but not all within!");
				}
				
				// order from largest to smallest
				Collections.reverse(indexes);
				
				for (int i : indexes) {
					ptsToCombine.add(pts.remove(i));
				}
				
				Point2D combinedPt;
				
				if (ptsToCombine.size() == 1) {
					// simple case, just maps to itself
					combinedPt = ptsToCombine.get(0);
				} else {
					// combine them
					double[] mags = new double[ptsToCombine.size()];
					double[] rates = new double[ptsToCombine.size()];
					for (int i=0; i<ptsToCombine.size(); i++) {
						Point2D pt = ptsToCombine.get(i);
						mags[i] = pt.getX();
						rates[i] = pt.getY();
					}
					double meanMag = calcScaledAverage(rates, mags);
					combinedPt = new Point2D.Double(meanMag, StatUtils.sum(rates));
				}
				Preconditions.checkState(!newMFD.hasX(combinedPt.getX()), "Duplicate!");
				newMFD.set(combinedPt);
			}
			combinedMFDs[r] = new LightFixedXFunc(newMFD);
		}
		
		return combinedMFDs;
	}
	
	static void checkIdentical(FaultSystemSolution sol1, FaultSystemSolution sol2, boolean checkERF) {
		System.out.println("Doing Identical Check");
		FaultSystemRupSet rupSet1 = sol1.getRupSet();
		FaultSystemRupSet rupSet2 = sol2.getRupSet();
		
		Preconditions.checkState(rupSet1.getNumRuptures() == rupSet2.getNumRuptures(), "Rup count wrong");
		Preconditions.checkState(rupSet1.getNumSections() == rupSet2.getNumSections(), "Sect count wrong");
		
		// create rupture mapping since rups could be in different order
		Table<IntHashSet, Double, Integer> rupSet2RupsToIndexesMap = HashBasedTable.create();
		for (int r=0; r<rupSet2.getNumRuptures(); r++) {
			System.out.println(Joiner.on(",").join(rupSet2.getSectionsIndicesForRup(r)));
			IntHashSet rupSects = new IntHashSet(rupSet2.getSectionsIndicesForRup(r));
			Double rake = rupSet2.getAveRakeForRup(r);
			if (rupSet2RupsToIndexesMap.contains(rupSects, rake)) {
				int prevIndex = rupSet2RupsToIndexesMap.get(rupSects, rake);
				Joiner j = Joiner.on(",");
				String message = "Duplicate rup found in rups2!";
				message += "\n\t"+prevIndex+" mag="+rupSet2.getMagForRup(prevIndex)
						+" area="+rupSet2.getAreaForRup(prevIndex)+" rake="+rupSet2.getAveRakeForRup(prevIndex)
						+"\n\tsects: "+j.join(rupSet2.getSectionsIndicesForRup(prevIndex));
				message += "\n\t"+r+" mag="+rupSet2.getMagForRup(r)
						+" area="+rupSet2.getAreaForRup(r)+" rake="+rupSet2.getAveRakeForRup(r)
						+"\n\tsects: "+j.join(rupSet2.getSectionsIndicesForRup(r));
				throw new IllegalStateException(message);
			}
			rupSet2RupsToIndexesMap.put(rupSects, rake, r);
		}
		// rup1 -> rup2
		Map<Integer, Integer> rupIDMapping = Maps.newHashMap();
		boolean idsIdentical = true;
		RupMFDsModule mfds1 = sol1.requireModule(RupMFDsModule.class);
		RupMFDsModule mfds2 = sol2.requireModule(RupMFDsModule.class);
		for (int r=0; r<rupSet1.getNumRuptures(); r++) {
			IntHashSet rupSects = new IntHashSet(rupSet1.getSectionsIndicesForRup(r));
			Double rake = rupSet1.getAveRakeForRup(r);
			Integer index = rupSet2RupsToIndexesMap.get(rupSects, rake);
			Preconditions.checkNotNull(index, "No mapping for rup "+r+" found in sol2");
			
			idsIdentical = idsIdentical && r == index.intValue();
			
			rupIDMapping.put(r, index);
			
			// check section ordering identical (or reversed)
			List<Integer> mySects = rupSet1.getSectionsIndicesForRup(r);
			List<Integer> otherSects = rupSet2.getSectionsIndicesForRup(index);
			Preconditions.checkState(mySects.size() == otherSects.size(), "Sect count wrong for rup "+r+"/"+index);
			if (mySects.get(0).intValue() != otherSects.get(0).intValue()) {
				// try reversing
				mySects = Lists.newArrayList(mySects);
				Collections.reverse(mySects);
			}
			boolean sectsEqual = mySects.equals(otherSects);
			if (!sectsEqual) {
				Joiner j = Joiner.on(",");
				throw new IllegalStateException("Sect ordering wrong for rup "+r+"/"+index+
						"\n\trup 1: "+j.join(mySects)+
						"\n\trup 2: "+j.join(otherSects));
			}
			
			// check rate equal
			checkFloatTolerance(sol1.getRateForRup(r), sol2.getRateForRup(index), "Rates wrong for rup "+r+"/"+index);
			
			// check mag equal
			checkFloatTolerance(rupSet1.getMagForRup(r), rupSet2.getMagForRup(index), "Mags wrong for rup "+r+"/"+index);
			
			// check area equal
			checkFloatTolerance(rupSet1.getAreaForRup(r), rupSet2.getAreaForRup(index), "Areas wrong for rup "+r+"/"+index);
			
			// check MFDs
			DiscretizedFunc mfd1 = mfds1.getRuptureMFD(r);
			DiscretizedFunc mfd2 = mfds2.getRuptureMFD(index);
			Preconditions.checkState(mfd1.size() == mfd2.size(), "MFD sizes inconsistant");
			for (int i=0; i<mfd1.size(); i++) {
				checkFloatTolerance(mfd1.getX(i), mfd2.getX(i), "Mags wrong for rup "+r+"/"+index+" mfd "+i);
				checkFloatTolerance(mfd1.getY(i), mfd2.getY(i), "Rates wrong for rup "+r+"/"+index+" mfd "+i);
			}
			
			// check rake
			checkFloatTolerance(rupSet1.getAveRakeForRup(r), rupSet2.getAveRakeForRup(index), "Rakes wrong for rup "+r+"/"+index);
		}
		rupSet2RupsToIndexesMap = null;
		System.gc();
		
		// check sections
		Map<String, Integer> rupSet2SectsToIndexesMap = Maps.newHashMap();
		for (int s=0; s<rupSet2.getNumSections(); s++) {
			String name = rupSet2.getFaultSectionData(s).getName();
			Preconditions.checkState(!rupSet2SectsToIndexesMap.containsKey(name), "Duplicate sect found in rups2!");
			rupSet2SectsToIndexesMap.put(name, s);
		}
		for (int s=0; s<rupSet1.getNumSections(); s++) {
			FaultSection sect1 = rupSet1.getFaultSectionData(s);
			String name = sect1.getName();
			Integer index = rupSet2SectsToIndexesMap.get(name);
			Preconditions.checkNotNull(index, "No mapping for sect "+s+" ("+name+") found in sol2");
			FaultSection sect2 = rupSet2.getFaultSectionData(index);
			
			// check orig upper depth
			checkFloatTolerance(sect1.getOrigAveUpperDepth(), sect2.getOrigAveUpperDepth(),
					"Orig upper depth wrong for sect "+s+"/"+index);

			// check reduced upper depth
			checkFloatTolerance(sect1.getReducedAveUpperDepth(), sect2.getReducedAveUpperDepth(),
					"Reduced upper depth wrong for sect "+s+"/"+index);

			// check orig upper depth
			checkFloatTolerance(sect1.getAveLowerDepth(), sect2.getAveLowerDepth(),
					"Lower depth wrong for sect "+s+"/"+index);

			// check rake
			if (Double.isNaN(sect1.getAveRake()))
				Preconditions.checkState(Double.isNaN(sect2.getAveRake()));
			else
				checkFloatTolerance(sect1.getAveRake(), sect2.getAveRake(),
						"Rake wrong for sect "+s+"/"+index);

			// check dip
			checkFloatTolerance(sect1.getAveDip(), sect2.getAveDip(),
					"Dip wrong for sect "+s+"/"+index);

			// check dip direction
			checkFloatTolerance(sect1.getDipDirection(), sect2.getDipDirection(),
					"Dip direction wrong for sect "+s+"/"+index);
		}
		rupSet2SectsToIndexesMap = null;
		System.gc();
		
		// now actually check ERFs
		if (checkERF) {
			System.out.println("Doing ERF check");
			int erfBinSize = 100000;
			for (int i=0; i<rupSet1.getNumRuptures(); i+=erfBinSize) {
				System.gc();
				System.out.println("ERF subset "+i);
				List<Integer> rups1 = Lists.newArrayList();
				List<Integer> rups2 = Lists.newArrayList();
				
				for (int r=i; r<rupSet1.getNumRuptures()&&r<(i+erfBinSize); r++) {
					rups1.add(r);
					rups2.add(rupIDMapping.get(r));
				}
				
				FaultSystemSolutionERF erf1 = new FaultSystemSolutionERF(new SubsetSolution(sol1, rups1));
				FaultSystemSolutionERF erf2 = new FaultSystemSolutionERF(new SubsetSolution(sol2, rups2));
				
				erf1.setParameter(IncludeBackgroundParam.NAME, IncludeBackgroundOption.EXCLUDE);
				erf2.setParameter(IncludeBackgroundParam.NAME, IncludeBackgroundOption.EXCLUDE);
				
				erf1.updateForecast();
				erf2.updateForecast();
				
				Preconditions.checkState(erf1.getNumSources() == erf2.getNumSources(), "Source count wrong!");
				for (int s=0; s<erf1.getNumSources(); s++) {
					ProbEqkSource source1 = erf1.getSource(s);
					ProbEqkSource source2 = erf2.getSource(s);
					
					Preconditions.checkState(source1.getNumRuptures() == source2.getNumRuptures(), "Rup count wrong for source "+(s+i));
					
					checkFloatTolerance(source1.computeTotalProb(), source2.computeTotalProb(), "Probs wrong for source "+(s+i));
					
					for (int r=0; r<source1.getNumRuptures(); r++) {
						ProbEqkRupture rup1 = source1.getRupture(r);
						ProbEqkRupture rup2 = source2.getRupture(r);
						checkFloatTolerance(rup1.getProbability(), rup2.getProbability(), "Probs wrong for source "+(s+i)+" rup "+r);
						checkFloatTolerance(rup1.getMag(), rup2.getMag(), "Mags wrong for source "+(s+i)+" rup "+r);
						checkFloatTolerance(rup1.getAveRake(), rup2.getAveRake(), "Rakes wrong for source "+(s+i)+" rup "+r);
						checkFloatTolerance(rup1.getRuptureSurface().getArea(), rup2.getRuptureSurface().getArea(),
								"Areas wrong for source "+(s+i)+" rup "+r);
					}
				}
			}
		}
	}
	
	private static void checkFloatTolerance(double val1, double val2, String message) {
		Preconditions.checkState((float)val1 == (float)val2, message+" ("+(float)val1+" != "+(float)val2+")");
	}
	
	public static class SubsetSolution extends FaultSystemSolution {
		public SubsetSolution(FaultSystemSolution sol, List<Integer> rups) {
			super(new SubsetRupSet(sol.getRupSet(), rups), getSubArray(sol.getRateForAllRups(), rups));
			addModule(new RupMFDsModule(getSubArray(sol.getModule(RupMFDsModule.class).getRuptureMFDs(), rups)));
		}
	}
	
	public static class SubsetRupSet extends FaultSystemRupSet {

		public SubsetRupSet(FaultSystemRupSet rupSet, List<Integer> rups) {
			super(rupSet.getFaultSectionDataList(), getSubList(rupSet.getSectionIndicesForAllRups(), rups),
					getSubArray(rupSet.getMagForAllRups(), rups),
					getSubArray(rupSet.getAveRakeForAllRups(), rups),
					getSubArray(rupSet.getAreaForAllRups(), rups),
					getSubArray(rupSet.getLengthForAllRups(), rups));
			Preconditions.checkState(getNumRuptures() == rups.size());
		}
		
	}
	
	private static <E> List<List<E>> getSubList(List<List<E>> fullList, List<Integer> indexes) {
		List<List<E>> ret = Lists.newArrayList();
		for (int index : indexes)
			ret.add(fullList.get(index));
		return ret;
	}
	
	private static double[] getSubArray(double[] fullArray, List<Integer> indexes) {
		if (fullArray == null)
			return null;
		double[] ret = new double[indexes.size()];
		for (int i=0; i<indexes.size(); i++)
			ret[i] = fullArray[indexes.get(i)];
		return ret;
	}
	
	private static <E> E[] getSubArray(E[] fullArray, List<Integer> indexes) {
		if (fullArray == null)
			return null;
		E[] ret = Arrays.copyOf(fullArray, indexes.size());
		for (int i=0; i<indexes.size(); i++)
			ret[i] = fullArray[indexes.get(i)];
		return ret;
	}
	
	public static void main(String[] args) throws IOException, DocumentException {
		File cacheDir = MeanUCERF3.getStoreDir();
		FaultSystemSolution trueMean = U3FaultSystemIO.loadSol(new File(cacheDir, "mean_ucerf3_sol.zip"));
		FaultSystemSolution reduced = getCombinedSolution(trueMean, 1e-10, true, false, null);
//		FaultSystemSolution reduced = FaultSystemIO.loadSol(new File(cacheDir, "cached_dep1.0E-10_depShallow_rakeAll.zip"));
		
		checkIdentical(trueMean, reduced, true);
	}

}
