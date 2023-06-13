package org.opensha.sha.earthquake.faultSysSolution.modules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.opensha.commons.data.CSVFile;
import org.opensha.commons.geo.CubedGriddedRegion;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.modules.AverageableModule.AveragingAccumulator;
import org.opensha.commons.util.modules.helpers.CSV_BackedModule;
import org.opensha.commons.util.modules.helpers.FileBackedModule;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;

/**
 * Extension of {@link FaultGridAssociations} that also tracks 3D associations with fault cubes.
 * Gridded regions are further discretized at depths via a {@link CubedGriddedRegion}. Like
 * {@link FaultGridAssociations}, associations are stored both with and without accounting for
 * overlapping fault sections; you want to use the scaled values when distributing a quantity from
 * a cube to all associated sections, and the unscaled values when mapping a quantity from a section
 * to all associated cubes.
 * 
 * @author Kevin, Ned
 *
 */
public interface FaultCubeAssociations extends FaultGridAssociations {

	/**
	 * @return cubed gridded region
	 */
	CubedGriddedRegion getCubedGriddedRegion();
	
	/**
	 * @param cubeIndex
	 * @return section indexes associated with this cube, or null if none
	 */
	public int[] getSectsAtCube(int cubeIndex);
	
	/**
	 * @param cubeIndex
	 * @return section distance-fraction weights for this cube (scaled to account for overlap with other sections),
	 * returned in the same order as {@link #getSectsAtCube(int)}, or null if none.
	 */
	public double[] getScaledSectDistWeightsAtCube(int cubeIndex);
	
	/**
	 * 
	 * @param sectIndex
	 * @return the total weight for each section summed across all cubes, after accounting for any overlapping
	 * sections
	 */
	public double getTotalScaledDistWtAtCubesForSect(int sectIndex);
	
	/**
	 * @param cubeIndex
	 * @return section distance-fraction weights for this cube (without any scaling to account for overlap with other
	 * sections), returned in the same order as {@link #getSectsAtCube(int)}, or null if none.
	 */
	public double[] getOrigSectDistWeightsAtCube(int cubeIndex);
	
	/**
	 * 
	 * @param sectIndex
	 * @return the total weight for each section summed across all cubes, not accounting for any overlapping sections
	 */
	public double getTotalOrigDistWtAtCubesForSect(int sectIndex);

	@Override
	public default AveragingAccumulator<FaultGridAssociations> averagingAccumulator() {
		return new CubeIfPossibleAverager();
	}

	public static final String ARCHIVE_CUBE_ASSOC_FILE_NAME = "cube_association_weights.csv";
	public static final String ARCHIVE_CUBE_SECT_ASSOC_SUM_FILE_NAME = "cube_sect_association_sums.csv";
	
	public static class Precomputed extends FaultGridAssociations.Precomputed implements FaultCubeAssociations {
		
		private CubedGriddedRegion cgr;
		
		private ZipFile sourceZip;
		private String sourceZipEntryPrefix;
		
		// for each cube, an array of mapped section indexes (or null if none)
		private int[][] sectsAtCubes;
		// for each cube, an array of mapped section distance-fraction wts (where the wts represent the fraction of
		// seismicity assigned to the fault section below the min seismo mag), in the same order as sectsAtCubes
		private double[][] sectOrigDistWeightsAtCubes;
		// same as sectOrigDistWeightsAtCubes, but scaled to account for other faults also being mapped to individual cubes
		private double[][] sectScaledDistWeightsAtCubes;
		// this is the total wt for each section summed from sectOrigDistWeightsAtCubes, not scaled for overlap
		private double[] totOrigDistWtsAtCubesForSectArray;
		// this is the total wt for each section summed from sectDistWeightsAtCubes (divide the wt directly above
		// by this value to get the nucleation fraction for the section in the associated cube) 
		private double[] totScaledDistWtsAtCubesForSectArray;
		
		protected Precomputed() {
			
		}
		
		public Precomputed(FaultCubeAssociations associations) {
			super(associations);
			this.cgr = associations.getCubedGriddedRegion();
			// need the version with cube properties added
			this.regionFeature = cgr.toFeature();
			
			sectsAtCubes = new int[getCubedGriddedRegion().getNumCubes()][];
			sectOrigDistWeightsAtCubes = new double[sectsAtCubes.length][];
			sectScaledDistWeightsAtCubes = new double[sectsAtCubes.length][];
			
			int maxSectIndex = 0;
			for (int c=0; c<sectsAtCubes.length; c++) {
				sectsAtCubes[c] = associations.getSectsAtCube(c);
				if (sectsAtCubes[c] != null) {
					sectOrigDistWeightsAtCubes[c] = associations.getOrigSectDistWeightsAtCube(c);
					sectScaledDistWeightsAtCubes[c] = associations.getScaledSectDistWeightsAtCube(c);
					for (int sectIndex : sectsAtCubes[c])
						maxSectIndex = Integer.max(maxSectIndex, sectIndex);
				}
			}
			
			totOrigDistWtsAtCubesForSectArray = new double[maxSectIndex+1];
			totScaledDistWtsAtCubesForSectArray = new double[maxSectIndex+1];
			for (int s=0; s<totOrigDistWtsAtCubesForSectArray.length; s++) {
				totOrigDistWtsAtCubesForSectArray[s] = associations.getTotalOrigDistWtAtCubesForSect(s);
				totScaledDistWtsAtCubesForSectArray[s] = associations.getTotalScaledDistWtAtCubesForSect(s);
			}
		}
		
		private Precomputed(FaultGridAssociations gridAssociations, CubedGriddedRegion cgr, int[][] sectsAtCubes,
				double[][] sectOrigDistWeightsAtCubes, double[][] sectScaledDistWeightsAtCubes,
				double[] totOrigDistWtsAtCubesForSectArray, double[] totScaledDistWtsAtCubesForSectArray) {
			super(gridAssociations);
			this.cgr = cgr;
			this.sectsAtCubes = sectsAtCubes;
			this.sectOrigDistWeightsAtCubes = sectOrigDistWeightsAtCubes;
			this.sectScaledDistWeightsAtCubes = sectScaledDistWeightsAtCubes;
			this.totOrigDistWtsAtCubesForSectArray = totOrigDistWtsAtCubesForSectArray;
			this.totScaledDistWtsAtCubesForSectArray = totScaledDistWtsAtCubesForSectArray;
		}

		@Override
		public String getName() {
			return "Fault Cube Associations";
		}

		@Override
		public CubedGriddedRegion getCubedGriddedRegion() {
			if (cgr == null) {
				synchronized (this) {
					if (cgr == null) {
						Preconditions.checkNotNull(regionFeature,
								"Feature must already be loaded to init a cgr (can't build it from a gridded region)");
						if (region == null) {
							// load both
							cgr = CubedGriddedRegion.fromFeature(regionFeature);
							region = cgr.getGriddedRegion();
						} else {
							// use already-loaded gridded region
							cgr = CubedGriddedRegion.fromFeature(region, regionFeature);
						}
					}
				}
			}
			return cgr;
		}
		
		private void checkLazyInit() {
			if (sectsAtCubes == null) {
				synchronized (this) {
					if (sectsAtCubes == null) {
						System.out.println("Lazily loading cube associations...");
						CSVFile<String> csv;
						try {
							csv = CSV_BackedModule.loadFromArchive(
									sourceZip, sourceZipEntryPrefix, ARCHIVE_CUBE_ASSOC_FILE_NAME);
						} catch (IOException e) {
							throw ExceptionUtils.asRuntimeException(e);
						}
						int[][] sectsAtCubes = new int[getCubedGriddedRegion().getNumCubes()][];
						double[][] sectOrigDistWeightsAtCubes = new double[sectsAtCubes.length][];
						double[][] sectScaledDistWeightsAtCubes = new double[sectsAtCubes.length][];
						
						int maxSectIndex = 0;
						for (int row=1; row<csv.getNumRows(); row++) {
							List<String> line = csv.getLine(row);
							int cubeIndex = Integer.parseInt(line.get(0));
							int numValCols = line.size()-1;
							if (numValCols % 3 == 2) {
								// probably missing last empty value (last orig val was equal to last scaled val and omitted)
								line = new ArrayList<>(line);
								line.add("");
								numValCols++;
							}
							int num = numValCols / 3;
							Preconditions.checkState(line.size() == num*3+1, "Malformed row: %s", line);
							sectsAtCubes[cubeIndex] = new int[num];
							sectOrigDistWeightsAtCubes[cubeIndex] = new double[num];
							// start with them pointing to the same data
							boolean sameArray = true;
							sectScaledDistWeightsAtCubes[cubeIndex] = sectOrigDistWeightsAtCubes[cubeIndex];
							
							int col = 1;
							for (int i=0; i<num; i++) {
								sectsAtCubes[cubeIndex][i] = Integer.parseInt(line.get(col++));
								maxSectIndex = Integer.max(maxSectIndex, sectsAtCubes[cubeIndex][i]);
								sectOrigDistWeightsAtCubes[cubeIndex][i] = Double.parseDouble(line.get(col++));
								String scaledWeightStr = line.get(col++);
								if (scaledWeightStr == null || scaledWeightStr.isBlank()) {
									// values are the same
									if (sameArray) {
										// do nothing, array is already pointing to the same data
									} else {
										// copy it over
										sectScaledDistWeightsAtCubes[cubeIndex][i] = sectOrigDistWeightsAtCubes[cubeIndex][i];
									}
								} else {
									// values differ
									double newVal = Double.parseDouble(scaledWeightStr);
									if (sameArray) {
										if (i == 0)
											// first one, just init
											sectScaledDistWeightsAtCubes[cubeIndex] = new double[num];
										else
											// need to copy it
											sectScaledDistWeightsAtCubes[cubeIndex] = Arrays.copyOf(sectOrigDistWeightsAtCubes[cubeIndex], num);
										sameArray = false;
									}
									sectScaledDistWeightsAtCubes[cubeIndex][i] = newVal;
								}
							}
						}
						
						try {
							csv = CSV_BackedModule.loadFromArchive(
									sourceZip, sourceZipEntryPrefix, ARCHIVE_CUBE_SECT_ASSOC_SUM_FILE_NAME);
						} catch (IOException e) {
							throw ExceptionUtils.asRuntimeException(e);
						}
						
						int numSects = Integer.max(maxSectIndex+1, csv.getNumRows()-1);
						
						double[] totOrigDistWtsAtCubesForSectArray = new double[numSects];
						double[] totScaledDistWtsAtCubesForSectArray = new double[numSects];
						for (int row=1; row<csv.getNumRows(); row++) {
							int sectIndex = csv.getInt(row, 0);
							totOrigDistWtsAtCubesForSectArray[sectIndex] = csv.getDouble(row, 1);
							totScaledDistWtsAtCubesForSectArray[sectIndex] = csv.getDouble(row, 2);
						}
						
						this.sectOrigDistWeightsAtCubes = sectOrigDistWeightsAtCubes;
						this.sectScaledDistWeightsAtCubes = sectScaledDistWeightsAtCubes;
						this.totOrigDistWtsAtCubesForSectArray = totOrigDistWtsAtCubesForSectArray;
						this.totScaledDistWtsAtCubesForSectArray = totScaledDistWtsAtCubesForSectArray;
						this.sectsAtCubes = sectsAtCubes;
						System.out.println("DONE Lazily loading cube associations...");
					}
				}
			}
		}

		@Override
		public int[] getSectsAtCube(int cubeIndex) {
			checkLazyInit();
			return sectsAtCubes[cubeIndex];
		}

		@Override
		public double[] getScaledSectDistWeightsAtCube(int cubeIndex) {
			checkLazyInit();
			return sectScaledDistWeightsAtCubes[cubeIndex];
		}

		@Override
		public double getTotalScaledDistWtAtCubesForSect(int sectIndex) {
			checkLazyInit();
			return sectIndex >= totScaledDistWtsAtCubesForSectArray.length ?
					0d : totScaledDistWtsAtCubesForSectArray[sectIndex];
		}

		@Override
		public double[] getOrigSectDistWeightsAtCube(int cubeIndex) {
			checkLazyInit();
			return sectOrigDistWeightsAtCubes[cubeIndex];
		}

		@Override
		public double getTotalOrigDistWtAtCubesForSect(int sectIndex) {
			checkLazyInit();
			return sectIndex >= totOrigDistWtsAtCubesForSectArray.length ?
					0d : totOrigDistWtsAtCubesForSectArray[sectIndex];
		}

		@Override
		public void writeToArchive(ZipOutputStream zout, String entryPrefix) throws IOException {
			// this will write the cubed region out automatically (data is stored in the feature)
			super.writeToArchive(zout, entryPrefix);
			
			if (sectsAtCubes == null) {
				Preconditions.checkNotNull(sourceZip, "Lazily initialized but no source zip?");
				// copy the stream directly
				System.out.println("Copying cube association data streams directly (without loading)");
				
				FileBackedModule.initEntry(zout, entryPrefix, ARCHIVE_CUBE_ASSOC_FILE_NAME);
				BufferedOutputStream out = new BufferedOutputStream(zout);
				
				BufferedInputStream zin = FileBackedModule.getInputStream(sourceZip, sourceZipEntryPrefix, ARCHIVE_CUBE_ASSOC_FILE_NAME);
				zin.transferTo(out);
				zin.close();
				
				out.flush();
				zout.closeEntry();
				
				FileBackedModule.initEntry(zout, entryPrefix, ARCHIVE_CUBE_SECT_ASSOC_SUM_FILE_NAME);
				out = new BufferedOutputStream(zout);
				
				zin = FileBackedModule.getInputStream(sourceZip, sourceZipEntryPrefix, ARCHIVE_CUBE_SECT_ASSOC_SUM_FILE_NAME);
				zin.transferTo(out);
				zin.close();
				
				out.flush();
				zout.closeEntry();
				
				return;
			}
			
			// write cube data CSV
			CSVFile<String> csv = new CSVFile<>(false);
			csv.addLine("Cube Index", "Sect Index 1", "Original Dist Weight 1", "Scaled Dist Weight 1 (if different)", "...");
			for (int c=0; c<sectsAtCubes.length; c++) {
				if (sectsAtCubes[c] != null && sectsAtCubes[c].length > 0) {
					// this cube has mappings
					List<String> row = new ArrayList<>(1+3*sectsAtCubes[c].length);
					row.add(c+"");
					for (int i=0; i<sectsAtCubes[c].length; i++) {
						row.add(sectsAtCubes[c][i]+"");
						row.add(sectOrigDistWeightsAtCubes[c][i]+"");
						if (sectScaledDistWeightsAtCubes[c][i] != sectOrigDistWeightsAtCubes[c][i])
							row.add(sectScaledDistWeightsAtCubes[c][i]+"");
						else
							row.add("");
					}
					csv.addLine(row);
				}
			}
			
			CSV_BackedModule.writeToArchive(csv, zout, entryPrefix, ARCHIVE_CUBE_ASSOC_FILE_NAME);
			
			// write sect sum CSV
			csv = new CSVFile<>(false);
			csv.addLine("Sect Index", "Total Original Dist Weight", "Total Scaled Dist Weight");
			for (int s=0; s<totOrigDistWtsAtCubesForSectArray.length; s++)
				csv.addLine(s+"", totOrigDistWtsAtCubesForSectArray[s]+"", totScaledDistWtsAtCubesForSectArray[s]+"");
			
			CSV_BackedModule.writeToArchive(csv, zout, entryPrefix, ARCHIVE_CUBE_SECT_ASSOC_SUM_FILE_NAME);
		}

		@Override
		public void initFromArchive(ZipFile zip, String entryPrefix) throws IOException {
			super.initFromArchive(zip, entryPrefix);
			
			// load associations lazily
			this.sourceZip = zip;
			this.sourceZipEntryPrefix = entryPrefix;
		}
		
	}
	
	/**
	 * This class aggregates cube data from a {@link FaultCubeAssociations} instance to provide
	 * node-specific data for the {@link FaultGridAssociations} interface.
	 * 
	 * @author kevin
	 *
	 */
	public static class CubeToGridNodeAggregator implements FaultGridAssociations {
		
		private FaultCubeAssociations cubeAssoc;
		
		/*
		 * Grid cell outputs
		 */
		private ImmutableSet<Integer> sectIndices;
		private ImmutableMap<Integer, Double> nodeExtents;
		
		// both are Table<SubSectionID, NodeIndex, Value>
		//
		// the percentage of each node spanned by each fault sub-section
		private ImmutableTable<Integer, Integer, Double> nodeInSectPartic;
		// same as above, scaled with percentage scaled to account for
		// multiple overlapping sub-sections
		private ImmutableTable<Integer, Integer, Double> sectInNodePartic;

		public CubeToGridNodeAggregator(FaultCubeAssociations cubeAssoc) {
			this.cubeAssoc = cubeAssoc;
			
			aggregateToGridNodes();
		}
		
		private void aggregateToGridNodes() {
			// now collapse them to grid nodes
			HashSet<Integer> sectIndices = new HashSet<>();
			ImmutableMap.Builder<Integer, Double> nodeExtentsBuilder = ImmutableMap.builder();
			ImmutableTable.Builder<Integer, Integer, Double> nodeInSectParticBuilder = ImmutableTable.builder();
			ImmutableTable.Builder<Integer, Integer, Double> sectInNodeParticBuilder = ImmutableTable.builder();
			
			CubedGriddedRegion cgr = cubeAssoc.getCubedGriddedRegion();
			GriddedRegion griddedRegion = cgr.getGriddedRegion();

			for (int nodeIndex=0; nodeIndex<griddedRegion.getNodeCount(); nodeIndex++) {
				int numCubes = 0;
				Map<Integer, Double> sectFracts = null;
				Map<Integer, Double> sectOrigFracts = null;
				for (int cubeIndex : cgr.getCubeIndicesForGridCell(nodeIndex)) {
					numCubes++;
					int[] sects = cubeAssoc.getSectsAtCube(cubeIndex);
					if (sects != null) {
						if (sectFracts == null) {
							sectFracts = new HashMap<>();
							sectOrigFracts = new HashMap<>();
						}
						double[] sectDistWts = cubeAssoc.getScaledSectDistWeightsAtCube(cubeIndex);
						double[] sectOrigDistWts = cubeAssoc.getOrigSectDistWeightsAtCube(cubeIndex);
						for (int s=0; s<sects.length; s++) {
							sectIndices.add(sects[s]);
							Double prevWt = sectFracts.get(sects[s]);
							Double prevOrigWt = sectOrigFracts.get(sects[s]);
							if (prevWt == null) {
								prevWt = 0d;
								prevOrigWt = 0d;
							}
							Preconditions.checkState(sectDistWts[s] >= 0d && sectDistWts[s] <= 1d,
									"Bad sectWeight for cube %s, sect %s: %s", cubeIndex, sects[s], sectDistWts[s]);
							sectFracts.put(sects[s], prevWt + sectDistWts[s]);
							Preconditions.checkState(sectOrigDistWts[s] >= 0d && sectOrigDistWts[s] <= 1d,
									"Bad origWeight for cube %s, sect %s: %s", cubeIndex, sects[s], sectOrigDistWts[s]);
							sectOrigFracts.put(sects[s], prevOrigWt + sectOrigDistWts[s]);
						}
					}
				}
				if (sectFracts != null) {
					double sumNodeWeight = 0d;
					for (Integer sectIndex : sectFracts.keySet()) {
						// this is the fraction of this node that is occupied by this section, scaled to account for
						// overlap with other sections
						double sectWeight = sectFracts.get(sectIndex)/(double)numCubes;
						Preconditions.checkState((float)sectWeight <= 1f && (float)sectWeight >= 0f,
								"Bad aggregated sectWeight for node %s, sect %s: %s", nodeIndex, sectIndex, sectWeight);
						sumNodeWeight += sectWeight;
						sectInNodeParticBuilder.put(sectIndex, nodeIndex, sectWeight);
						
						// this is the fraction of the section that maps to this node, e.g., if we were to spread the
						// nucleation rate for this section across all nodes, what fraction should be assigned to this one?
						
						double origWeight = sectOrigFracts.get(sectIndex)/cubeAssoc.getTotalOrigDistWtAtCubesForSect(sectIndex);
						Preconditions.checkState((float)origWeight <= 1f && (float)origWeight >= 0f,
								"Bad aggregated origWeight for node %s, sect %s: %s", nodeIndex, sectIndex, origWeight);
//						Preconditions.checkState((float)sectWeight <= (float)origWeight,
//								"aggregated final weight should be <= orig weight: sectWeight=%s, origWeight=%s", sectWeight, origWeight);
						nodeInSectParticBuilder.put(sectIndex, nodeIndex, origWeight);
					}
					nodeExtentsBuilder.put(nodeIndex, sumNodeWeight);
				}
			}

			this.sectIndices = ImmutableSet.copyOf(sectIndices);
			this.nodeExtents = nodeExtentsBuilder.build();
			this.nodeInSectPartic = nodeInSectParticBuilder.build();
			this.sectInNodePartic = sectInNodeParticBuilder.build();
		}

		@Override
		public String getName() {
			return cubeAssoc.getName();
		}

		@Override
		public Map<Integer, Double> getNodeExtents() {
			return ImmutableMap.copyOf(nodeExtents);
		}
		
		@Override
		public double getNodeFraction(int nodeIdx) {
			Double fraction = nodeExtents.get(nodeIdx);
			return (fraction == null) ? 0.0 : fraction;
		}
		
		@Override
		public Map<Integer, Double> getScaledNodeFractions(int sectIdx) {
			return sectInNodePartic.row(sectIdx);
		}
		
		@Override
		public Map<Integer, Double> getScaledSectFracsOnNode(int nodeIdx) {
			return sectInNodePartic.column(nodeIdx);
		}
		
		@Override
		public Map<Integer, Double> getNodeFractions(int sectIdx) {
			return nodeInSectPartic.row(sectIdx);
		}
		
		@Override
		public Map<Integer, Double> getSectionFracsOnNode(int nodeIdx) {
			return nodeInSectPartic.column(nodeIdx);
		}

		@Override
		public GriddedRegion getRegion() {
			return cubeAssoc.getCubedGriddedRegion().getGriddedRegion();
		}

		@Override
		public Collection<Integer> sectIndices() {
			return sectIndices;
		}
	}
	
	public static class CubeIfPossibleAverager implements AveragingAccumulator<FaultGridAssociations> {
		
		private FaultGridAssociations.Averager gridAverager;
		private CubeAverager cubeAverager;

		@Override
		public Class<FaultGridAssociations> getType() {
			return FaultGridAssociations.class;
		}

		@Override
		public void process(FaultGridAssociations module, double relWeight) {
			FaultCubeAssociations cubeModule = module instanceof FaultCubeAssociations ? (FaultCubeAssociations)module : null;
			if (gridAverager == null) {
				// first time
				if (cubeModule != null) {
					cubeAverager = new CubeAverager();
					gridAverager = cubeAverager.gridAverager;
				} else {
					// don't have cubes
					gridAverager = new FaultGridAssociations.Averager();
				}
			}
			
			if (cubeAverager != null && cubeModule != null) {
				// all cubes so far, use cube averager (which will also call process on the grid averager)
				cubeAverager.process(cubeModule, relWeight);
			} else {
				// we either don't have cubes now, or didn't at some point, just call process on grid averager
				cubeAverager = null;
				gridAverager.process(module, relWeight);
			}
		}

		@Override
		public FaultGridAssociations getAverage() {
			if (cubeAverager != null)
				return cubeAverager.getAverage();
			return gridAverager.getAverage();
		}
		
	}
	
	public static class CubeAverager implements AveragingAccumulator<FaultCubeAssociations> {
		
		private FaultGridAssociations.Averager gridAverager = new FaultGridAssociations.Averager();
		
		private double sumWeight = 0d;
		
		private CubedGriddedRegion cgr;
		
		// for each cube, an array of mapped section indexes (or null if none)
		private int[][] sectsAtCubes;
		// for each cube, an array of mapped section distance-fraction wts (where the wts represent the fraction of
		// seismicity assigned to the fault section below the min seismo mag), in the same order as sectsAtCubes
		private double[][] sectOrigDistWeightsAtCubes;
		// same as sectOrigDistWeightsAtCubes, but scaled to account for other faults also being mapped to individual cubes
		private double[][] sectScaledDistWeightsAtCubes;
		// this is the total wt for each section summed from sectOrigDistWeightsAtCubes, not scaled for overlap
		private double[] totOrigDistWtsAtCubesForSectArray;
		// this is the total wt for each section summed from sectDistWeightsAtCubes (divide the wt directly above
		// by this value to get the nucleation fraction for the section in the associated cube) 
		private double[] totScaledDistWtsAtCubesForSectArray;
		
		private int maxSectIndex = 0;

		@Override
		public Class<FaultCubeAssociations> getType() {
			return FaultCubeAssociations.class;
		}

		@Override
		public void process(FaultCubeAssociations module, double relWeight) {
			if (cgr == null)
				this.cgr = module.getCubedGriddedRegion();
			else
				Preconditions.checkState(module.getCubedGriddedRegion().getNumCubes() == this.cgr.getNumCubes());
			
			gridAverager.process(module, relWeight);
			
			if (sectsAtCubes == null) {
				// first time
				sectsAtCubes = new int[cgr.getNumCubes()][];
				sectOrigDistWeightsAtCubes = new double[sectsAtCubes.length][];
				sectScaledDistWeightsAtCubes = new double[sectsAtCubes.length][];
				
				for (int c=0; c<sectsAtCubes.length; c++) {
					int[] sects = module.getSectsAtCube(c);
					if (sects != null) {
						sectsAtCubes[c] = sects;
						sectOrigDistWeightsAtCubes[c] = Arrays.copyOf(module.getOrigSectDistWeightsAtCube(c), sects.length);
						sectScaledDistWeightsAtCubes[c] = Arrays.copyOf(module.getScaledSectDistWeightsAtCube(c), sects.length);
						// scale by weights
						for (int s=0; s<sects.length; s++) {
							sectOrigDistWeightsAtCubes[c][s] *= relWeight;
							sectScaledDistWeightsAtCubes[c][s] *= relWeight;
							maxSectIndex = Integer.max(maxSectIndex, sects[s]);
						}
					}
				}
				
				totOrigDistWtsAtCubesForSectArray = new double[maxSectIndex+1];
				totScaledDistWtsAtCubesForSectArray = new double[maxSectIndex+1];
				for (int s=0; s<maxSectIndex; s++) {
					totOrigDistWtsAtCubesForSectArray[s] = module.getTotalOrigDistWtAtCubesForSect(s)*relWeight;
					totScaledDistWtsAtCubesForSectArray[s] = module.getTotalScaledDistWtAtCubesForSect(s)*relWeight;
				}
			} else {
				// additional module
				for (int c=0; c<sectsAtCubes.length; c++) {
					int[] sects = module.getSectsAtCube(c);
					if (sects != null) {
						if (sectsAtCubes[c] == null) {
							// this one maps, previous didn't, copy it over
							sectOrigDistWeightsAtCubes[c] = Arrays.copyOf(module.getOrigSectDistWeightsAtCube(c), sects.length);
							sectScaledDistWeightsAtCubes[c] = Arrays.copyOf(module.getScaledSectDistWeightsAtCube(c), sects.length);
							// scale by weights
							for (int s=0; s<sects.length; s++) {
								sectOrigDistWeightsAtCubes[c][s] *= relWeight;
								sectScaledDistWeightsAtCubes[c][s] *= relWeight;
								maxSectIndex = Integer.max(maxSectIndex, sects[s]);
							}
						} else {
							// add them in
							double[] moduleOrigWeights = module.getOrigSectDistWeightsAtCube(c);
							double[] moduleScaledWeights = module.getScaledSectDistWeightsAtCube(c);
							for (int s=0; s<sects.length; s++) {
								int sectIndex = sects[s];
								maxSectIndex = Integer.max(maxSectIndex, sectIndex);
								int matchIndex;
								if (s < sectsAtCubes[c].length && sectsAtCubes[c][s] == sectIndex) {
									// simple same index match
									matchIndex = s;
								} else {
									// need to match them
									matchIndex = -1;
									for (int s1=0; s1<sectsAtCubes[c].length; s1++) {
										if (sectsAtCubes[c][s1] == sectIndex) {
											matchIndex = s1;
											break;
										}
									}
									if (matchIndex < 0) {
										// new mapping, need to expand arrays
										matchIndex = sectsAtCubes[c].length;
										sectsAtCubes[c] = Arrays.copyOf(sectsAtCubes[c], matchIndex+1);
										sectsAtCubes[c][matchIndex] = sectIndex;
										sectOrigDistWeightsAtCubes[c] = Arrays.copyOf(sectOrigDistWeightsAtCubes[c], matchIndex+1);
										sectScaledDistWeightsAtCubes[c] = Arrays.copyOf(sectScaledDistWeightsAtCubes[c], matchIndex+1);
									}
								}
								sectOrigDistWeightsAtCubes[c][matchIndex] += moduleOrigWeights[s]*relWeight;
								sectScaledDistWeightsAtCubes[c][matchIndex] += moduleScaledWeights[s]*relWeight;
							}
						}
					}
				}
				
				if (totOrigDistWtsAtCubesForSectArray.length < maxSectIndex+1) {
					totOrigDistWtsAtCubesForSectArray = Arrays.copyOf(totOrigDistWtsAtCubesForSectArray, maxSectIndex+1);
					totScaledDistWtsAtCubesForSectArray = Arrays.copyOf(totScaledDistWtsAtCubesForSectArray, maxSectIndex+1);
				}
				for (int s=0; s<maxSectIndex; s++) {
					totOrigDistWtsAtCubesForSectArray[s] += module.getTotalOrigDistWtAtCubesForSect(s)*relWeight;
					totScaledDistWtsAtCubesForSectArray[s] += module.getTotalScaledDistWtAtCubesForSect(s)*relWeight;
				}
			}
			
			sumWeight += relWeight;
		}

		@Override
		public FaultCubeAssociations getAverage() {
			FaultGridAssociations gridAssoc = gridAverager.getAverage();
			
			// rescale for total weight
			for (int c=0; c<sectsAtCubes.length; c++) {
				if (sectsAtCubes[c] != null) {
					boolean scaledEqual = true;
					for (int s=0; s<sectsAtCubes[c].length; s++) {
						scaledEqual &= sectOrigDistWeightsAtCubes[c][s] == sectScaledDistWeightsAtCubes[c][s];
						sectOrigDistWeightsAtCubes[c][s] /= sumWeight;
						sectScaledDistWeightsAtCubes[c][s] /= sumWeight;
					}
					if (scaledEqual)
						sectScaledDistWeightsAtCubes[c] = sectOrigDistWeightsAtCubes[c];
				}
			}
			for (int i=0; i<totOrigDistWtsAtCubesForSectArray.length; i++) {
				totOrigDistWtsAtCubesForSectArray[i] /= sumWeight;
				totScaledDistWtsAtCubesForSectArray[i] /= sumWeight;
			}
			return new Precomputed(gridAssoc, cgr, sectsAtCubes,
					sectOrigDistWeightsAtCubes, sectScaledDistWeightsAtCubes,
					totOrigDistWtsAtCubesForSectArray, totScaledDistWtsAtCubesForSectArray);
		}
		
	}

}
