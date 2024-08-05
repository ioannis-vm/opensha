package org.opensha.sha.earthquake.faultSysSolution.modules;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.opensha.commons.data.CSVFile;
import org.opensha.commons.data.CSVReader;
import org.opensha.commons.data.CSVReader.Row;
import org.opensha.commons.data.CSVWriter;
import org.opensha.commons.data.Site;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.geo.LocationList;
import org.opensha.commons.geo.LocationUtils;
import org.opensha.commons.geo.Region;
import org.opensha.commons.geo.json.Feature;
import org.opensha.commons.util.DataUtils;
import org.opensha.commons.util.FaultUtils;
import org.opensha.commons.util.modules.ArchivableModule;
import org.opensha.commons.util.modules.helpers.CSV_BackedModule;
import org.opensha.commons.util.modules.helpers.FileBackedModule;
import org.opensha.sha.earthquake.ProbEqkRupture;
import org.opensha.sha.earthquake.ProbEqkSource;
import org.opensha.sha.earthquake.param.BackgroundRupType;
import org.opensha.sha.faultSurface.FiniteApproxPointSurface;
import org.opensha.sha.faultSurface.PointSurface;
import org.opensha.sha.faultSurface.RuptureSurface;
import org.opensha.sha.faultSurface.utils.PointSurfaceBuilder;
import org.opensha.sha.magdist.IncrementalMagFreqDist;
import org.opensha.sha.util.FocalMech;
import org.opensha.sha.util.TectonicRegionType;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.primitives.Ints;

public class GridSourceList implements GridSourceProvider, ArchivableModule {
	
	private LocationList locs;
	private GriddedRegion gridReg; // can be null
	
	private IncrementalMagFreqDist refMFD; // used for getMFD(...) methods
	
	// the actual rupture data
	private EnumMap<TectonicRegionType, ImmutableList<ImmutableList<GriddedRupture>>> trtRuptureLists;
	
	// mappings from source index to tectonic region type and grid index
	private TectonicRegionType[] sourceTRTs;
	private int[] sourceGridIndexes;
	
	// mapping from associated sections to grid nodes
	private Map<Integer, Set<Integer>> sectAssociations;
	
	private double sourceMinMag = 5d;
	
	private GridSourceList() {}
	
	public GridSourceList(LocationList locs, EnumMap<TectonicRegionType, ? extends List<? extends List<GriddedRupture>>> trtRuptureLists) {
		setAll(null, locs, trtRuptureLists);
	}
	
	public GridSourceList(GriddedRegion gridReg, EnumMap<TectonicRegionType, ? extends List<? extends List<GriddedRupture>>> trtRuptureLists) {
		setAll(gridReg, gridReg.getNodeList(), trtRuptureLists);
	}
	
	public GridSourceList(GriddedRegion gridReg, TectonicRegionType trt, List<? extends List<GriddedRupture>> ruptureLists) {
		EnumMap<TectonicRegionType, List<? extends List<GriddedRupture>>> trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
		trtRuptureLists.put(trt, ruptureLists);
		setAll(gridReg, gridReg.getNodeList(), trtRuptureLists);
	}
	
	private void setAll(GriddedRegion gridReg, LocationList locs,
			EnumMap<TectonicRegionType, ? extends List<? extends List<GriddedRupture>>> trtRuptureLists) {
		Preconditions.checkNotNull(locs);
		if (gridReg != null)
			Preconditions.checkState(locs.size() == gridReg.getNodeCount(),
					"Location list has %s locations, gridded region has %s", locs.size(), gridReg.getNodeCount());
		int sourceCount = 0;
		for (TectonicRegionType trt : trtRuptureLists.keySet())
			for (List<GriddedRupture> ruptures : trtRuptureLists.get(trt))
				if (ruptures != null && !ruptures.isEmpty())
					sourceCount++;
		
		TectonicRegionType[] sourceTRTs = new TectonicRegionType[sourceCount];
		int[] sourceGridIndexes = new int[sourceCount];
		
		EnumMap<TectonicRegionType, ImmutableList<ImmutableList<GriddedRupture>>> trtRuptureListsOut = new EnumMap<>(TectonicRegionType.class);
		double minMag = Double.POSITIVE_INFINITY;
		double maxMag = Double.NEGATIVE_INFINITY;
		boolean magsTenthAligned = true;
		int numRups = 0;
		int sourceIndex = 0;
		HashMap<Integer, Set<Integer>> sectAssociations = new HashMap<>();
		for (TectonicRegionType trt : TectonicRegionType.values()) {
			List<? extends List<GriddedRupture>> ruptureLists = trtRuptureLists.get(trt);
			if (ruptureLists == null)
				continue;
			Preconditions.checkState(ruptureLists.size() == locs.size());
			ImmutableList.Builder<ImmutableList<GriddedRupture>> ruptureListsBuilder = ImmutableList.builder();
			for (int gridIndex=0; gridIndex<locs.size(); gridIndex++) {
				Location gridLoc = locs.get(gridIndex);
				List<GriddedRupture> ruptures = ruptureLists.get(gridIndex);
				if (ruptures == null || ruptures.isEmpty()) {
					ruptureListsBuilder.add(ImmutableList.of());
				} else {
					for (GriddedRupture rup : ruptures) {
						Preconditions.checkState(rup.tectonicRegionType == trt);
						Preconditions.checkState(LocationUtils.areSimilar(rup.location, gridLoc));
						numRups++;
						minMag = Math.min(minMag, rup.magnitude);
						maxMag = Math.max(maxMag, rup.magnitude);
						// detect the case where ruptures are directly on the tenths (e.g., 5.0, 5.1)
						magsTenthAligned &= (float)(rup.magnitude*10d) == (float)Math.floor(rup.magnitude*10d);
						
						if (rup.associatedSections != null) {
							for (int sectID : rup.associatedSections) {
								Set<Integer> sectNodes = sectAssociations.get(sectID);
								if (sectNodes == null) {
									sectNodes = new HashSet<>();
									sectAssociations.put(sectID, sectNodes);
								}
								sectNodes.add(gridIndex);
							}
						}
					}
					
					ruptureListsBuilder.add(ImmutableList.copyOf(ruptures));
					sourceTRTs[sourceIndex] = trt;
					sourceGridIndexes[sourceIndex] = gridIndex;
					sourceIndex++;
				}
			}
			trtRuptureListsOut.put(trt, ruptureListsBuilder.build());
		}
		Preconditions.checkState(sourceIndex == sourceCount,
				"Source count mismatch; expected=%s, sourceIndex=%s after last", sourceCount, sourceIndex);
		Preconditions.checkState(numRups > 0, "Must supply at least 1 rupture to determine MFD gridding");
		double delta = 0.1;
		if (!magsTenthAligned) {
			// align to 0.x5 bins (so that bin edges are at tenths)
			minMag = Math.floor(minMag*10d)/10d + 0.5*delta;
			maxMag = Math.floor(maxMag*10d)/10d + 0.5*delta;
		}
		int size = (int)Math.round((maxMag - minMag)/delta) + 1;
		refMFD = new IncrementalMagFreqDist(minMag, size, delta);
		
		this.trtRuptureLists = trtRuptureListsOut;
		this.locs = locs;
		this.gridReg = gridReg;
		this.sourceTRTs = sourceTRTs;
		this.sourceGridIndexes = sourceGridIndexes;
		this.sectAssociations = sectAssociations;
	}

	@Override
	public void setSourceMinMagCutoff(double minMagCutoff) {
		this.sourceMinMag = minMagCutoff;
	}

	@Override
	public double getSourceMinMagCutoff() {
		return getSourceMinMagCutoff();
	}

	@Override
	public String getName() {
		return "Grid Source List";
	}

	@Override
	public AveragingAccumulator<GridSourceProvider> averagingAccumulator() {
		return new Averager();
	}

	@Override
	public int getNumLocations() {
		return locs.size();
	}

	@Override
	public Location getLocation(int index) {
		return locs.get(index);
	}

	@Override
	public Location getLocationForSource(int sourceIndex) {
		return getLocation(sourceGridIndexes[sourceIndex]);
	}

	@Override
	public Set<TectonicRegionType> getTectonicRegionTypes() {
		return trtRuptureLists.keySet();
	}

	@Override
	public int getNumSources() {
		return sourceGridIndexes.length;
	}
	
	public int locationIndexForSourceIndex(int sourceIndex) {
		return sourceGridIndexes[sourceIndex];
	}
	
	public TectonicRegionType tectonicRegionTypeForSourceIndex(int sourceIndex) {
		return sourceTRTs[sourceIndex];
	}
	
	/**
	 * @param sectionIndex
	 * @return set of associated grid indexes, or empty set if none
	 */
	public Set<Integer> getAssociatedGridIndexes(int sectionIndex) {
		Set<Integer> ret = sectAssociations.get(sectionIndex);
		if (ret == null)
			return Set.of();
		return ret;
	}
	
	/**
	 * 
	 * @return reference (empty) MFD for use determining magnitude range and discreization of getMFD methods
	 */
	public IncrementalMagFreqDist getRefMFD() {
		return refMFD;
	}
	
	public ImmutableList<GriddedRupture> getRuptures(TectonicRegionType tectonicRegionType, int gridIndex) {
		if (tectonicRegionType == null) {
			ImmutableList.Builder<GriddedRupture> listBuilder = ImmutableList.builder();
			for (TectonicRegionType trt : trtRuptureLists.keySet())
				listBuilder.addAll(trtRuptureLists.get(trt).get(gridIndex));
			return listBuilder.build();
		}
		ImmutableList<ImmutableList<GriddedRupture>> trtList = trtRuptureLists.get(tectonicRegionType);
		if (trtList == null)
			return ImmutableList.of();
		return trtList.get(gridIndex);
	}
	
	public ImmutableList<GriddedRupture> getAssociatedRuptures(int sectionIndex) {
		Set<Integer> gridIndexes = sectAssociations.get(sectionIndex);
		if (gridIndexes == null)
			return ImmutableList.of();
		ImmutableList.Builder<GriddedRupture> ret = ImmutableList.builder();
		
		for (int gridIndex : gridIndexes)
			for (TectonicRegionType trt : getTectonicRegionTypes())
				for (GriddedRupture rup : getRuptures(trt, gridIndex))
					if (rup.associatedSections != null && Ints.contains(rup.associatedSections, sectionIndex))
						ret.add(rup);
		
		return ret.build();
	}
	
	public ImmutableList<GriddedRupture> getRupturesSubSeisOnFault(TectonicRegionType tectonicRegionType, int gridIndex) {
		ImmutableList.Builder<GriddedRupture> subSeisRups = ImmutableList.builder();
		for (GriddedRupture rup : getRuptures(tectonicRegionType, gridIndex))
			if (rup.associatedSections != null && rup.associatedSections.length > 0)
				subSeisRups.add(rup);
		return subSeisRups.build();
	}
	
	public ImmutableList<GriddedRupture> getRupturesUnassociated(TectonicRegionType tectonicRegionType, int gridIndex) {
		ImmutableList.Builder<GriddedRupture> unassocRups = ImmutableList.builder();
		for (GriddedRupture rup : getRuptures(tectonicRegionType, gridIndex))
			if (rup.associatedSections == null || rup.associatedSections.length == 0)
				unassocRups.add(rup);
		return unassocRups.build();
	}

	@Override
	public ProbEqkSource getSource(int sourceIndex, double duration, DoubleBinaryOperator aftershockFilter,
			BackgroundRupType bgRupType) {
		return getSource(tectonicRegionTypeForSourceIndex(sourceIndex), locationIndexForSourceIndex(sourceIndex),
				duration, aftershockFilter, bgRupType);
	}

	@Override
	public ProbEqkSource getSource(TectonicRegionType tectonicRegionType, int gridIndex, double duration,
			DoubleBinaryOperator aftershockFilter, BackgroundRupType bgRupType) {
		return new GriddedRuptureSource(getLocation(gridIndex), getRuptures(tectonicRegionType, gridIndex),
				duration, sourceMinMag, aftershockFilter, bgRupType, tectonicRegionType);
	}

	@Override
	public ProbEqkSource getSourceSubSeisOnFault(TectonicRegionType tectonicRegionType, int gridIndex, double duration, DoubleBinaryOperator aftershockFilter,
			BackgroundRupType bgRupType) {
		return new GriddedRuptureSource(getLocation(gridIndex), getRupturesSubSeisOnFault(tectonicRegionType, gridIndex),
				duration, sourceMinMag, aftershockFilter, bgRupType, tectonicRegionType);
	}

	@Override
	public ProbEqkSource getSourceUnassociated(TectonicRegionType tectonicRegionType, int gridIndex, double duration, DoubleBinaryOperator aftershockFilter,
			BackgroundRupType bgRupType) {
		return new GriddedRuptureSource(getLocation(gridIndex), getRupturesUnassociated(tectonicRegionType, gridIndex),
				duration, sourceMinMag, aftershockFilter, bgRupType, tectonicRegionType);
	}

	@Override
	public IncrementalMagFreqDist getMFD_Unassociated(TectonicRegionType tectonicRegionType, int gridIndex) {
		return getMFD(tectonicRegionType, gridIndex, Double.NEGATIVE_INFINITY, true, false);
	}

	@Override
	public IncrementalMagFreqDist getMFD_SubSeisOnFault(TectonicRegionType tectonicRegionType, int gridIndex) {
		return getMFD(tectonicRegionType, gridIndex, Double.NEGATIVE_INFINITY, false, true);
	}

	@Override
	public IncrementalMagFreqDist getMFD(TectonicRegionType tectonicRegionType, int gridIndex, double minMag) {
		return getMFD(tectonicRegionType, gridIndex, minMag, true, true);
	}

	@Override
	public IncrementalMagFreqDist getMFD(TectonicRegionType tectonicRegionType, int gridIndex) {
		return getMFD(tectonicRegionType, gridIndex, Double.NEGATIVE_INFINITY, true, true); 
	}

	@Override
	public IncrementalMagFreqDist getMFD_Unassociated(int gridIndex) {
		return getMFD(null, gridIndex, Double.NEGATIVE_INFINITY, true, false);
	}

	@Override
	public IncrementalMagFreqDist getMFD_SubSeisOnFault(int gridIndex) {
		return getMFD(null, gridIndex, Double.NEGATIVE_INFINITY, false, true);
	}

	@Override
	public IncrementalMagFreqDist getMFD(int gridIndex, double minMag) {
		return getMFD(null, gridIndex, minMag, true, true);
	}

	@Override
	public IncrementalMagFreqDist getMFD(int gridIndex) {
		return getMFD(null, gridIndex, Double.NEGATIVE_INFINITY, true, true); 
	}
	
	private IncrementalMagFreqDist getMFD(TectonicRegionType tectonicRegionType, int gridIndex, double minMag,
			boolean includeUnassociated, boolean includeAssociated) {
		IncrementalMagFreqDist mfd;
		if (Double.isFinite(minMag)) {
			int minIndex = refMFD.getClosestXIndex(minMag);
			mfd = new IncrementalMagFreqDist(refMFD.getX(minIndex), refMFD.size()-minIndex, refMFD.getDelta());
			// reset minMag to be the bin edge
			minMag = mfd.getMinX() - 0.5*mfd.getDelta();
		} else {
			mfd = refMFD.deepClone();
		}
		int maxIndexNonZero = 0;
		for (GriddedRupture rup : getRuptures(tectonicRegionType, gridIndex)) {
			if (rup.magnitude >= minMag && rup.rate >= 0d) {
				int index = mfd.getClosestXIndex(rup.magnitude);
				mfd.add(index, rup.rate);
				maxIndexNonZero = Integer.max(maxIndexNonZero, index);
			}
		}
		if (maxIndexNonZero < mfd.size()-1) {
			// trim it
			IncrementalMagFreqDist trimmed = new IncrementalMagFreqDist(mfd.getMinX(), maxIndexNonZero+1, mfd.getDelta());
			for (int i=0; i<trimmed.size(); i++)
				trimmed.set(i, mfd.getY(i));
			mfd = trimmed;
		}
		return mfd;
	}

	@Override
	public GriddedRegion getGriddedRegion() {
		return gridReg;
	}
	
	public GridSourceList getFilteredForMinMag(float minMag) {
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> filteredMap = new EnumMap<>(TectonicRegionType.class);
		
		for (TectonicRegionType trt : getTectonicRegionTypes()) {
			List<List<GriddedRupture>> filteredLists = new ArrayList<>(getNumLocations());
			for (int gridIndex=0; gridIndex<getNumLocations(); gridIndex++) {
				ImmutableList<GriddedRupture> rups = getRuptures(trt, gridIndex);
				if (rups.isEmpty()) {
					filteredLists.add(null);
					continue;
				}
				List<GriddedRupture> filteredRups = new ArrayList<>();
				
				for (GriddedRupture rup : rups)
					if ((float)rup.magnitude >= minMag)
						filteredRups.add(rup);
				
				if (filteredRups.isEmpty())
					filteredLists.add(null);
				else
					filteredLists.add(filteredRups);
			}
			filteredMap.put(trt, filteredLists);
		}
		
		GridSourceList ret = new GridSourceList();
		ret.setAll(gridReg, locs, filteredMap);
		if (minMag > (float)ret.sourceMinMag)
			ret.setSourceMinMagCutoff(minMag);
		return ret;
	}
	
	private static List<Range<Double>> SS_RANGES = List.of(
			Range.closedOpen(-180d, -135d),
			Range.open(-45d, 45d),
			Range.openClosed(135d, 180d));
	private static Range<Double> REV_RANGE = Range.closed(45d, 135d);
	private static Range<Double> NORM_RANGE = Range.closed(-135d, -45d);

	@Override
	public double getFracStrikeSlip(int gridIndex) {
		return getFractWithRake(SS_RANGES, gridIndex);
	}

	@Override
	public double getFracReverse(int gridIndex) {
		return getFractWithRake(REV_RANGE, gridIndex);
	}

	@Override
	public double getFracNormal(int gridIndex) {
		return getFractWithRake(NORM_RANGE, gridIndex);
	}
	
	private double getFractWithRake(Range<Double> rakeRange, int gridIndex) {
		return getFractWithRake(List.of(rakeRange), gridIndex);
	}
	
	private double getFractWithRake(List<Range<Double>> rakeRanges, int gridIndex) {
		double totRate = 0d;
		double rateMatching = 0d;
		for (TectonicRegionType trt : getTectonicRegionTypes()) {
			ImmutableList<GriddedRupture> rups = getRuptures(trt, gridIndex);
			for (GriddedRupture rup : rups) {
				totRate += rup.rate;
				for (Range<Double> range : rakeRanges) {
					if (range.contains(rup.rake)) {
						rateMatching += rup.rate;
					}
				}
			}
		}
		if (totRate == 0d)
			return 0d;
		return rateMatching/totRate;
	}

	@Override
	public void scaleAll(double[] valuesArray) {
		for (TectonicRegionType trt : getTectonicRegionTypes())
			scaleAll(trt, valuesArray);
	}

	@Override
	public void scaleAll(TectonicRegionType tectonicRegionType, double[] valuesArray) {
		Preconditions.checkState(valuesArray.length == getNumLocations(),
				"Scale value size mismatch: %s != %s", valuesArray.length, getNumLocations());
		ImmutableList.Builder<ImmutableList<GriddedRupture>> modRupListBuilder = ImmutableList.builder();
		ImmutableList<ImmutableList<GriddedRupture>> ruptureLists = trtRuptureLists.get(tectonicRegionType);
		for (int i=0; i<valuesArray.length; i++) {
			ImmutableList<GriddedRupture> origRups = ruptureLists.get(i);
			if (valuesArray[i] == 0d) {
				modRupListBuilder.add(ImmutableList.of());
			} else if (valuesArray[i] == 1d) {
				modRupListBuilder.add(origRups);
			} else {
				ImmutableList.Builder<GriddedRupture> modRupBuilder = ImmutableList.builder();
				for (GriddedRupture rup : origRups)
					modRupBuilder.add(rup.copyNewRate(rup.rake*valuesArray[i]));
				modRupListBuilder.add(modRupBuilder.build());
			}
		}
		trtRuptureLists.put(tectonicRegionType, modRupListBuilder.build());
	}
	
	public static final String ARCHIVE_GRID_LOCS_FILE_NAME = "grid_source_locations.csv";
	public static final String ARCHIVE_GRID_SOURCES_FILE_NAME = "grid_sources.csv";
	
	private static final int locRoundScale = 3;
	private static final int magRoundScale = 3;
	private static final int mechRoundSigFigs = 3;
	private static final int depthRoundSigFigs = 3;
	private static final int lenRoundSigFigs = 3;
	private static final int rateRoundSigFigs = 6;
	
	private boolean round = true;
	
	public void setArhiveRounding(boolean round) {
		this.round = true;
	}
	
	public CSVFile<String> buildGridLocsCSV() {
		CSVFile<String> gridCSV = new CSVFile<>(true);
		gridCSV.addLine("Grid Index", "Latitude", "Longitude");
		for (int i=0; i<getNumLocations(); i++) {
			Location loc = getLocation(i);
			gridCSV.addLine(i+"", getFixedPrecision(loc.lat, locRoundScale), getFixedPrecision(loc.lon, locRoundScale));
		}
		return gridCSV;
	}
	
	public void writeGridSourcesCSV(ZipOutputStream zout, String entryName) throws IOException {
		// use CSVWriter for efficiency
		ZipEntry entry = new ZipEntry(entryName);
		zout.putNextEntry(entry);
		CSVWriter rupCSV = new CSVWriter(zout, false);
		List<String> header = new ArrayList<>();
		header.add("Grid Index");
		header.add("Magnitude");
		header.add("Annual Rate");
		header.add("Rake");
		header.add("Dip");
		header.add("Strike");
		header.add("Upper Depth (km)");
		header.add("Lower Depth (km)");
		header.add("Length (km)");
		header.add("Hypocentral Depth (km)");
		header.add("Hypocentral DAS (km)");
		header.add("Tectonic Regime");
		int maxNumAssoc = 0;
		for (TectonicRegionType trt : getTectonicRegionTypes())
			for (int i=0; i<gridReg.getNodeCount(); i++)
				for (GriddedRupture rup : getRuptures(trt, i))
					if (rup.associatedSections != null)
						maxNumAssoc = Integer.max(maxNumAssoc, rup.associatedSections.length);
		if (maxNumAssoc > 0) {
			if (maxNumAssoc == 1) {
				header.add("Associated Section Index");
				header.add("Fraction Associated");
			} else {
				header.add("Associated Section Index 1");
				header.add("Fraction Associated 1");
				header.add("Associated Section Index N");
				header.add("Fraction Associated N");
			}
		}
		rupCSV.write(header);
		for (int i=0; i<gridReg.getNodeCount(); i++) { 
			for (TectonicRegionType trt : getTectonicRegionTypes()) {
				for (GriddedRupture rup : getRuptures(trt, i)) {
					List<String> line = new ArrayList<>();
					line.add(i+"");
					line.add(getFixedPrecision(rup.magnitude, magRoundScale));
					line.add(getSigFigs(rup.rate, rateRoundSigFigs));
					line.add(getSigFigs(rup.rake, mechRoundSigFigs));
					line.add(getSigFigs(rup.dip, mechRoundSigFigs));
					if (rup.strikeRange != null)
						line.add(rangeToString(rup.strikeRange));
					else
						line.add(getSigFigs(rup.strike, mechRoundSigFigs));
					line.add(getSigFigs(rup.upperDepth, depthRoundSigFigs));
					line.add(getSigFigs(rup.lowerDepth, depthRoundSigFigs));
					line.add(getSigFigs(rup.length, lenRoundSigFigs));
					line.add(getSigFigs(rup.hypocentralDepth, depthRoundSigFigs));
					line.add(getSigFigs(rup.hypocentralDAS, lenRoundSigFigs));
					line.add(rup.tectonicRegionType.name());
					if (rup.associatedSections != null) {
						for (int s=0; s<rup.associatedSections.length; s++) {
							line.add(rup.associatedSections[s]+"");
							line.add(getSigFigs(rup.associatedSectionFracts[s], rateRoundSigFigs)+"");
						}
					}
					rupCSV.write(line);
				}
			}
		}
		rupCSV.flush();
		zout.closeEntry();
	}

	@Override
	public void writeToArchive(ZipOutputStream zout, String entryPrefix) throws IOException {
		if (gridReg != null) {
			// write the gridded region
			FileBackedModule.initEntry(zout, entryPrefix, GridSourceProvider.ARCHIVE_GRID_REGION_FILE_NAME);
			Feature regFeature = gridReg.toFeature();
			OutputStreamWriter writer = new OutputStreamWriter(zout);
			Feature.write(regFeature, writer);
			writer.flush();
			zout.flush();
			zout.closeEntry();
		}
		
		// write grid locations
		CSV_BackedModule.writeToArchive(buildGridLocsCSV(), zout, entryPrefix, ARCHIVE_GRID_LOCS_FILE_NAME);
		
		// write gridded rupture list
		writeGridSourcesCSV(zout, ArchivableModule.getEntryName(entryPrefix, ARCHIVE_GRID_SOURCES_FILE_NAME));
	}
	
	private static boolean isRangeString(String str) {
		str = str.trim();
		return str.startsWith("[") && str.endsWith("]");
	}
	
	private static Range<Double> parseRangeString(String str) {
		str = str.trim();
		Preconditions.checkState(isRangeString(str));
		str = str.substring(1, str.length()-1);
		String firstStr, lastStr;
		if (str.contains(",")) {
			int commaIndex = str.indexOf(",");
			firstStr = str.substring(0, commaIndex);
			lastStr = str.substring(commaIndex+1);
		} else {
			Preconditions.checkState(str.contains(".."));
			int dotsIndex = str.indexOf("..");
			firstStr = str.substring(0, dotsIndex);
			lastStr = str.substring(dotsIndex+2);
		}
		double lower = Double.parseDouble(firstStr.trim());
		double upper = Double.parseDouble(lastStr.trim());
		return Range.closed(lower, upper);
	}
	
	private String rangeToString(Range<Double> range) {
		Preconditions.checkState(range.hasLowerBound() && range.hasUpperBound(), "Must have fixed founds");
		return "["+getSigFigs(range.lowerEndpoint(), mechRoundSigFigs)+".."+getSigFigs(range.upperEndpoint(), mechRoundSigFigs)+"]";
	}
	
	private String getFixedPrecision(double val, int scale) {
		if (Double.isNaN(val))
			return "";
		if (!Double.isFinite(val))
			return val+"";
		if (val == Math.floor(val))
			return (int)val+"";
		if (!round)
			return val+"";
		return DataUtils.roundFixed(val, scale)+"";
	}
	
	private String getSigFigs(double val, int sigFigs) {
		if (Double.isNaN(val))
			return "";
		if (!Double.isFinite(val))
			return val+"";
		if (val == Math.floor(val))
			return (int)val+"";
		if (!round)
			return val+"";
		return DataUtils.roundSigFigs(val, sigFigs)+"";
	}
	
	public static LocationList loadGridLocsCSV(CSVFile<String> gridCSV, GriddedRegion gridReg) {
		LocationList fileLocs = new LocationList();
		for (int row=1; row<gridCSV.getNumRows(); row++) {
			int index = row-1;
			int csvIndex = gridCSV.getInt(row, 0);
			Preconditions.checkState(csvIndex == index,
					"Grid locations must be in order, expected index=%s for row=%s, encountered index=%s",
					index, row, csvIndex);
			double lat = gridCSV.getDouble(row, 1);
			double lon = gridCSV.getDouble(row, 2);
			fileLocs.add(new Location(lat, lon));
		}
		if (gridReg == null)
			// use the CSV file directly
			return fileLocs;
		// use the gridded region, but validate
		Preconditions.checkState(gridReg.getNodeCount() == fileLocs.size(),
				"Gridded region has %s nodes, but %s has %s", gridReg.getNodeCount(), ARCHIVE_GRID_LOCS_FILE_NAME, fileLocs.size());
		for (int i=0; i<fileLocs.size(); i++) {
			Location fileLoc = fileLocs.get(i);
			Location gridLoc = gridReg.getLocation(i);
			if (!LocationUtils.areSimilar(fileLoc, gridLoc)) {
				// check rounding
				Location roundedLoc = new Location(DataUtils.roundFixed(gridLoc.lat, locRoundScale),
						DataUtils.roundFixed(gridLoc.lon, locRoundScale));
				Preconditions.checkState(LocationUtils.areSimilar(fileLoc, roundedLoc),
						"Location mismatch at index=%s between gridded region and %s: %s != %s;"
								+ "also tried test with rounded form: %s != %s",
								i, ARCHIVE_GRID_LOCS_FILE_NAME, gridLoc, fileLoc, roundedLoc, fileLoc);
			}
		}
		return gridReg.getNodeList();
	}
	
	public static EnumMap<TectonicRegionType, List<List<GriddedRupture>>> loadGridSourcesCSV(CSVReader rupSectsCSV, LocationList locs) {
		rupSectsCSV.read(); // skip header row
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
		while (true) {
			Row row = rupSectsCSV.read();
			if (row == null)
				break;
			
			int col = 0;
			int gridIndex = row.getInt(col++);
			Preconditions.checkState(gridIndex >= 0 && gridIndex < locs.size(),
					"Bad gridIndex=%s with %s locations", gridIndex, locs.size());
			double mag = row.getDouble(col++);
			Preconditions.checkState(Double.isFinite(mag), "Bad magnitude=%s", mag);
			double rate = row.getDouble(col++);
			Preconditions.checkState(Double.isFinite(rate) && rate >= 0d, "Bad rate=%s", rate);
			double rake = row.getDouble(col++);
			FaultUtils.assertValidRake(rake);
			double dip = row.getDouble(col++);
			FaultUtils.assertValidDip(dip);
			String strikeStr = row.get(col++);
			Range<Double> strikeRange = null;
			double strike = Double.NaN;
			if (isRangeString(strikeStr))
				strikeRange = parseRangeString(strikeStr);
			else if (!strikeStr.isBlank())
				strike = Double.parseDouble(strikeStr);
			double upperDepth = row.getDouble(col++);
			FaultUtils.assertValidDepth(upperDepth);
			double lowerDepth = row.getDouble(col++);
			FaultUtils.assertValidDepth(lowerDepth);
			double length = row.getDouble(col++);
			Preconditions.checkState(Double.isFinite(length) && length >= 0d, "Bad length=%s", length);
			String hypocentralDepthStr = row.get(col++);
			double hypocentralDepth = hypocentralDepthStr.isBlank() ? Double.NaN : Double.parseDouble(hypocentralDepthStr);
			if (!Double.isNaN(hypocentralDepth)) {
				FaultUtils.assertValidDepth(hypocentralDepth);
				Preconditions.checkState((float)hypocentralDepth >= (float)upperDepth,
						"Hypocentral depth (%s) must be at or below upper depth (%s)", (float)hypocentralDepth, (float)upperDepth);
				Preconditions.checkState((float)hypocentralDepth <= (float)lowerDepth,
						"Hypocentral depth (%s) must be at or above lower depth (%s)", (float)hypocentralDepth, (float)lowerDepth);
			}
			String hypocentralDASStr = row.get(col++);
			double hypocentralDAS = hypocentralDASStr.isBlank() ? Double.NaN : Double.parseDouble(hypocentralDASStr);
			if (!Double.isNaN(hypocentralDAS)) {
				Preconditions.checkState(hypocentralDAS >= 0d && (float)hypocentralDAS <= (float)length,
						"Hypocentral DAS (%s) must be >= 0 and <= len (%s)", (float)hypocentralDAS, (float)length);
			}
			TectonicRegionType tectonicRegionType = TectonicRegionType.valueOf(row.get(col++));
			int colsLeft = row.columns() - col;
			int[] associatedSections = null;
			double[] associatedSectionFracts = null;
			if (colsLeft > 0) {
				Preconditions.checkState(colsLeft % 2 == 0,
						"Have %s columns left for associations, which is not divisible by 2; expected pairs of id, fract",
						colsLeft);
				int numAssoc = colsLeft/2;
				associatedSections = new int[numAssoc];
				associatedSectionFracts = new double[numAssoc];
				for (int i=0; i<numAssoc; i++) {
					String sectStr = row.get(col++);
					if (sectStr.isBlank()) {
						// empty, bail here
						if (i == 0) {
							// didn't actually have any
							associatedSections = null;
							associatedSectionFracts = null;
						} else {
							// trim
							associatedSections = Arrays.copyOf(associatedSections, i);
							associatedSectionFracts = Arrays.copyOf(associatedSectionFracts, i);
						}
						break;
					} else {
						int sectID = Integer.parseInt(sectStr);
						Preconditions.checkState(sectID >= 0, "Bad associated sectID=%s", sectID);
						double fract = row.getDouble(col++);
						Preconditions.checkState(fract >= 0d && fract <= 1d, "Bad associated fraction=%s", fract);
						associatedSections[i] = sectID;
						associatedSectionFracts[i] = fract;
					}
				}
			}
			GriddedRupture rup = new GriddedRupture(gridIndex, locs.get(gridIndex), mag, rate, rake, dip,
					strike, strikeRange, upperDepth, lowerDepth, length, hypocentralDepth, hypocentralDAS,
					tectonicRegionType, associatedSections, associatedSectionFracts);
			if (!trtRuptureLists.containsKey(tectonicRegionType)) {
				List<List<GriddedRupture>> ruptureLists = new ArrayList<>(locs.size());
				for (int i=0; i<locs.size(); i++)
					ruptureLists.add(null);
				trtRuptureLists.put(tectonicRegionType, ruptureLists);
			}
			List<List<GriddedRupture>> ruptureLists = trtRuptureLists.get(tectonicRegionType);
			if (ruptureLists.get(gridIndex) == null)
				ruptureLists.set(gridIndex, new ArrayList<>());
			ruptureLists.get(gridIndex).add(rup);
		}
		return trtRuptureLists;
	}

	@Override
	public void initFromArchive(ZipFile zip, String entryPrefix) throws IOException {
		// load gridded region (if supplied)
		GriddedRegion gridReg = null;
		if (FileBackedModule.hasEntry(zip, entryPrefix, GridSourceProvider.ARCHIVE_GRID_REGION_FILE_NAME)) {
			BufferedInputStream regionIS = FileBackedModule.getInputStream(zip, entryPrefix, GridSourceProvider.ARCHIVE_GRID_REGION_FILE_NAME);
			InputStreamReader regionReader = new InputStreamReader(regionIS);
			Feature regFeature = Feature.read(regionReader);
			gridReg = GriddedRegion.fromFeature(regFeature);
		}
		
		// load grid location CSV
		CSVFile<String> gridCSV = CSV_BackedModule.loadFromArchive(zip, entryPrefix, ARCHIVE_GRID_LOCS_FILE_NAME);
		LocationList locs = loadGridLocsCSV(gridCSV, gridReg);
		
		// load ruptures themselves
		CSVReader rupSectsCSV = CSV_BackedModule.loadLargeFileFromArchive(zip, entryPrefix, ARCHIVE_GRID_SOURCES_FILE_NAME);
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists = loadGridSourcesCSV(rupSectsCSV, locs);
		setAll(gridReg, locs, trtRuptureLists);
	}
	
	/**
	 * Gridded rupture representation.
	 * 
	 * Note that hashCode() and equals() do not consider the rate, and can thus be used to find identical ruptures
	 * when combining multiple models (e.g., averaging or adding).
	 */
	public static class GriddedRupture implements Comparable<GriddedRupture> {
		// LOCATION
		public final int gridIndex;
		public final Location location;
		// MAGNITUDE
		public final double magnitude;
		// RATE
		public final double rate;
		// FOCAL MECHANISM
		public final double rake;
		public final double dip;
		public final double strike;
		public final Range<Double> strikeRange;
		// FINITE PROPERTIES
		public final double upperDepth;
		public final double lowerDepth;
		public final double length;
		public final double hypocentralDepth;
		public final double hypocentralDAS;
		// TECTONIC REGIME
		public final TectonicRegionType tectonicRegionType;
		// ASSOCIATIONS
		public final int[] associatedSections;
		public final double[] associatedSectionFracts;
		
		private transient int hashCode = -1;
		
		public GriddedRupture(int gridIndex, Location location, double magnitude, double rate, double rake, double dip,
				double strike, Range<Double> strikeRange, double upperDepth, double lowerDepth, double length,
				double hypocentralDepth, double hypocentralDAS, TectonicRegionType tectonicRegionType) {
			this(gridIndex, location, magnitude, rate, rake, dip, strike, strikeRange,
					upperDepth, lowerDepth, length, hypocentralDepth, hypocentralDAS, tectonicRegionType, null, null);
		}
		
		public GriddedRupture(int gridIndex, Location location, double magnitude, double rate, double rake, double dip,
				double strike, Range<Double> strikeRange, double upperDepth, double lowerDepth, double length,
				double hypocentralDepth, double hypocentralDAS, TectonicRegionType tectonicRegionType,
				int[] associatedSections, double[] associatedSectionFracts) {
			super();
			this.gridIndex = gridIndex;
			this.location = location;
			this.magnitude = magnitude;
			this.rate = rate;
			this.rake = rake;
			this.dip = dip;
			this.strike = strike;
			this.strikeRange = strikeRange;
			this.upperDepth = upperDepth;
			this.lowerDepth = lowerDepth;
			this.length = length;
			this.hypocentralDepth = hypocentralDepth;
			this.hypocentralDAS = hypocentralDAS;
			this.tectonicRegionType = tectonicRegionType;
			this.associatedSections = associatedSections;
			this.associatedSectionFracts = associatedSectionFracts;
		}
		
		public GriddedRupture copyNewRate(double modRate) {
			return new GriddedRupture(gridIndex, location, magnitude, modRate,
					rake, dip, strike, strikeRange, upperDepth, lowerDepth,
					length, hypocentralDepth, hypocentralDAS, tectonicRegionType, associatedSections, associatedSectionFracts);
		}
		
		public GriddedRupture copyNewGridIndex(int gridIndex) {
			return new GriddedRupture(gridIndex, location, magnitude, rate,
					rake, dip, strike, strikeRange, upperDepth, lowerDepth,
					length, hypocentralDepth, hypocentralDAS, tectonicRegionType, associatedSections, associatedSectionFracts);
		}
		
		/**
		 * Get's the hypocentral depth, calculating it (assuming middle depth) if the stored hypocentralDepth parameter is not set
		 * @return
		 */
		public double getHypocentralDepth() {
			if (Double.isFinite(hypocentralDepth))
				return hypocentralDepth;
			if (lowerDepth == upperDepth)
				return upperDepth;
			return upperDepth + 0.5*(lowerDepth - upperDepth);
		}
		
		/**
		 * @return the fractional DAS value, or 0.5 if not set
		 */
		public double getFractionalHypocentralDAS() {
			if (Double.isFinite(hypocentralDAS))
				return hypocentralDAS/length;
			return 0.5;
		}
		
		/**
		 * Get's the hypocentral DAS, calculating it (assuming middle along strike) if the stored hypocentralDAS parameter is not set
		 * @return
		 */
		public double getHypocentralDAS() {
			if (Double.isFinite(hypocentralDAS))
				return hypocentralDAS;
			return 0.5*length;
		}
		
		public double getFractAssociated(int sectionIndex) {
			if (associatedSections == null)
				return 0d;
			for (int i=0; i<associatedSections.length; i++)
				if (associatedSections[i] == sectionIndex)
					return associatedSectionFracts[i];
			return 0d;
		}

		@Override
		public int hashCode() {
			// cache hashCode to make hashing faster as all fields are immutable
			if (hashCode == -1)
				hashCode = calcHashCode();
			return hashCode;
		}

		public int calcHashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(associatedSectionFracts);
			result = prime * result + Arrays.hashCode(associatedSections);
			result = prime * result + Objects.hash(dip, gridIndex, hypocentralDAS, hypocentralDepth, length, lowerDepth, magnitude, rake,
					strike, strikeRange, tectonicRegionType, upperDepth);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			if (hashCode() != obj.hashCode())
				return false;
			GriddedRupture other = (GriddedRupture) obj;
			return Arrays.equals(associatedSectionFracts, other.associatedSectionFracts)
					&& Arrays.equals(associatedSections, other.associatedSections)
					&& Double.doubleToLongBits(dip) == Double.doubleToLongBits(other.dip)
					&& gridIndex == other.gridIndex
					&& Double.doubleToLongBits(hypocentralDAS) == Double.doubleToLongBits(other.hypocentralDAS)
					&& Double.doubleToLongBits(hypocentralDepth) == Double.doubleToLongBits(other.hypocentralDepth)
					&& Double.doubleToLongBits(length) == Double.doubleToLongBits(other.length)
					&& Double.doubleToLongBits(lowerDepth) == Double.doubleToLongBits(other.lowerDepth)
					&& Double.doubleToLongBits(magnitude) == Double.doubleToLongBits(other.magnitude)
					&& Double.doubleToLongBits(rake) == Double.doubleToLongBits(other.rake)
					&& Double.doubleToLongBits(strike) == Double.doubleToLongBits(other.strike)
					&& Objects.equals(strikeRange, other.strikeRange) && tectonicRegionType == other.tectonicRegionType
					&& Double.doubleToLongBits(upperDepth) == Double.doubleToLongBits(other.upperDepth);
		}

		@Override
		public int compareTo(GriddedRupture other) {
			return RUP_FULL_PROPS_COMPARATOR.compare(this, other);
		}
	}
	
	private static Comparator<GriddedRupture> RUP_NON_AVERAGED_PROPS_COMPARATOR = new GriddedRuptureComparator(true);
	
	private static Comparator<GriddedRupture> RUP_FULL_PROPS_COMPARATOR = new GriddedRuptureComparator(false);
	
	private static class GriddedRuptureComparator implements Comparator<GriddedRupture> {
		
		private boolean averageQuantitiesOnly;
		
		private GriddedRuptureComparator(boolean averageQuantitiesOnly) {
			this.averageQuantitiesOnly = averageQuantitiesOnly;
		}

		@Override
		public int compare(GriddedRupture rup1, GriddedRupture rup2) {
			int result;

			result = Integer.compare(rup1.gridIndex, rup2.gridIndex);
			if (result != 0) return result;

			result = Double.compare(rup1.magnitude, rup2.magnitude);
			if (result != 0) return result;

			result = Double.compare(rup1.rake, rup2.rake);
			if (result != 0) return result;

			result = Double.compare(rup1.dip, rup2.dip);
			if (result != 0) return result;

			result = Double.compare(rup1.strike, rup2.strike);
			if (result != 0) return result;

			if (rup1.strikeRange == null && rup2.strikeRange != null) return -1;
			if (rup1.strikeRange != null && rup2.strikeRange == null) return 1;
			if (rup1.strikeRange != null && rup2.strikeRange != null) {
				result = rup1.strikeRange.lowerEndpoint().compareTo(rup2.strikeRange.lowerEndpoint());
				if (result != 0) return result;
				result = rup1.strikeRange.upperEndpoint().compareTo(rup2.strikeRange.upperEndpoint());
				if (result != 0) return result;
			}
			
			// treat each unique hypocentral depth as separate, but we'll average quantities that affect DDW (and length)
			result = Double.compare(rup1.getHypocentralDepth(), rup2.getHypocentralDepth());
			if (result != 0) return result;
			// same with fractional DAS--we don't want to average explicitly set DAS values
			result = Double.compare(rup1.getFractionalHypocentralDAS(), rup2.getFractionalHypocentralDAS());

			if (!averageQuantitiesOnly) {
				result = Double.compare(rup1.upperDepth, rup2.upperDepth);
				if (result != 0) return result;

				result = Double.compare(rup1.lowerDepth, rup2.lowerDepth);
				if (result != 0) return result;

				result = Double.compare(rup1.length, rup2.length);
				if (result != 0) return result;

				result = Double.compare(rup1.hypocentralDAS, rup2.hypocentralDAS);
				if (result != 0) return result;

				result = Double.compare(rup1.hypocentralDepth, rup2.hypocentralDepth);
				if (result != 0) return result;
			}

			result = rup1.tectonicRegionType.compareTo(rup2.tectonicRegionType);
			if (result != 0) return result;

			result = compareIntArrays(rup1.associatedSections, rup2.associatedSections);
			if (result != 0) return result;

			result = compareDoubleArrays(rup1.associatedSectionFracts, rup2.associatedSectionFracts);
			return result;
		}
		
	}

	private static int compareIntArrays(int[] a, int[] b) {
		if (a == null && b == null) return 0;
		if (a == null && b != null) return -1;
		if (a != null && b == null) return 1;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int result = Integer.compare(a[i], b[i]);
			if (result != 0) return result;
		}
		return Integer.compare(a.length, b.length);
	}

	private static int compareDoubleArrays(double[] a, double[] b) {
		if (a == null && b == null) return 0;
		if (a == null && b != null) return -1;
		if (a != null && b == null) return 1;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int result = Double.compare(a[i], b[i]);
			if (result != 0) return result;
		}
		return Integer.compare(a.length, b.length);
	}
	
	public static class GriddedRuptureSource extends ProbEqkSource {
		
		private PointSurface sourceSurf;
		private Location gridLoc;
		private List<ProbEqkRupture> ruptures;
		
		public GriddedRuptureSource(Location gridLoc, List<GriddedRupture> gridRups, double duration, double minMag,
				DoubleBinaryOperator aftershockFilter, BackgroundRupType bgRupType, TectonicRegionType tectonicRegionType) {
			this.gridLoc = gridLoc;
			this.sourceSurf = new PointSurface(gridLoc);
			PointSurfaceBuilder surfBuilder = new PointSurfaceBuilder(gridLoc);
			ruptures = new ArrayList<>();
			for (GriddedRupture rup : gridRups) {
				if (rup.magnitude < minMag)
					continue;
				Preconditions.checkState(tectonicRegionType == rup.tectonicRegionType);
				surfBuilder.magnitude(rup.magnitude);
				surfBuilder.dip(rup.dip);
				if (Double.isFinite(rup.strike)) {
					surfBuilder.strike(rup.strike);
				} else if (rup.strikeRange != null) {
					surfBuilder.strikeRange(rup.strikeRange);
				} else {
					surfBuilder.strike(Double.NaN);
				}
				surfBuilder.upperDepth(rup.upperDepth);
				surfBuilder.lowerDepth(rup.lowerDepth);
				surfBuilder.length(rup.length);
				double hypoDepth = rup.getHypocentralDepth();
				surfBuilder.hypocentralDepth(hypoDepth);
				surfBuilder.das(rup.getHypocentralDAS());
				RuptureSurface[] surfs = surfBuilder.build(bgRupType);
				double rate = rup.rate;
				if (aftershockFilter != null)
					rate = aftershockFilter.applyAsDouble(rup.magnitude, rup.rate);
				if (rate == 0d)
					continue;
				double rateEach = surfs.length == 1 ? rate : rate/(double)surfs.length;
				double probEach = 1 - Math.exp(-rateEach * duration);
				for (RuptureSurface surf : surfs) {
					if (surf instanceof FiniteApproxPointSurface)
						// TODO: hack to get nshmp corrected rJB until we revamp the framework
						((FiniteApproxPointSurface)surf).setDistCorrMagAndType(rup.magnitude, null);
					ruptures.add(new ProbEqkRupture(rup.magnitude, rup.rake, probEach, surf,
							new Location(rup.location.lat, rup.location.lon, hypoDepth)));
				}
			}
			this.setTectonicRegionType(tectonicRegionType);
		}

		@Override
		public LocationList getAllSourceLocs() {
			LocationList locList = new LocationList();
			locList.add(gridLoc);
			return locList;
		}

		@Override
		public RuptureSurface getSourceSurface() {
			return sourceSurf;
		}

		@Override
		public double getMinDistance(Site site) {
			return LocationUtils.horzDistanceFast(site.getLocation(), gridLoc);
		}

		@Override
		public int getNumRuptures() {
			return ruptures.size();
		}

		@Override
		public ProbEqkRupture getRupture(int nRupture) {
			return ruptures.get(nRupture);
		}
		
	}
	
	private static class RupturePropertyAverager {
		private final PropAverager rateAverager = new PropAverager();
		private final PropAverager upperDepthAverager = new PropAverager();
		private final PropAverager lowerDepthAverager = new PropAverager();
		private final PropAverager lengthAverager = new PropAverager();
		private final PropAverager dasAverager = new PropAverager();
		private final PropAverager hypoDepthAverager = new PropAverager();
		private int[] associatedSectIDs = null;
		private double[] associatedFracts = null;
		
		private boolean explicitHalfDAS = false;
		private boolean explicitHalfDepth = false;
		
		private GriddedRupture firstRup;
		
		public void add(GriddedRupture rup, double weight) {
			if (firstRup == null)
				firstRup = rup;
			rateAverager.add(rup.rate, weight);
			upperDepthAverager.add(rup.upperDepth, weight);
			lowerDepthAverager.add(rup.lowerDepth, weight);
			lengthAverager.add(rup.length, weight);
			
			double halfLength = 0.5*rup.length;
			if (Double.isNaN(rup.hypocentralDAS)) {
				dasAverager.add(halfLength, weight);
			} else {
				if ((float)rup.hypocentralDAS == (float)halfLength)
					explicitHalfDAS = true;
				dasAverager.add(rup.hypocentralDAS, weight);
			}
			
			double midDepth = rup.upperDepth + 0.5*(rup.lowerDepth - rup.upperDepth);
			if (Double.isNaN(rup.hypocentralDepth)) {
				hypoDepthAverager.add(midDepth, weight);
			} else {
				if ((float)rup.hypocentralDepth == midDepth)
					explicitHalfDepth = true;
				hypoDepthAverager.add(rup.hypocentralDepth, weight);
			}
			
			if (rup.associatedSections != null) {
				if (associatedSectIDs == null) {
					associatedSectIDs = rup.associatedSections;
					associatedFracts = new double[associatedSectIDs.length];
					for (int i=0; i<associatedFracts.length; i++)
						associatedFracts[i] = rup.associatedSectionFracts[i]*weight;
				} else {
					for (int i=0; i<rup.associatedSections.length; i++) {
						int index = Ints.indexOf(associatedSectIDs, rup.associatedSections[i]);
						if (index < 0) {
							// new association
							associatedSectIDs = Arrays.copyOf(associatedSectIDs, associatedSectIDs.length+1);
							associatedFracts = Arrays.copyOf(associatedFracts, associatedFracts.length+1);
							associatedSectIDs[associatedSectIDs.length-1] = rup.associatedSections[i];
							associatedFracts[associatedFracts.length-1] = rup.associatedSectionFracts[i]*weight;
						} else {
							// repeat association
							associatedFracts[index] += rup.associatedSectionFracts[i]*weight;
						}
					}
				}
			}
		}
		
		public GriddedRupture build(GriddedRupture ref, double sumWeights) {
			Preconditions.checkArgument(ref.gridIndex == firstRup.gridIndex,
					"Grid index mismatch? %s != %s", ref.gridIndex, firstRup.gridIndex);
			Preconditions.checkArgument((float)ref.magnitude == (float)firstRup.magnitude,
					"Mag mismatch? %s != %s", (float)ref.magnitude, (float)firstRup.magnitude);
			Preconditions.checkArgument(ref.tectonicRegionType == firstRup.tectonicRegionType,
					"TRT mismatch? %s != %s", ref.tectonicRegionType, firstRup.tectonicRegionType);
			double upper = upperDepthAverager.getAverage(sumWeights);
			double lower = lowerDepthAverager.getAverage(sumWeights);
			double hypoDepth = hypoDepthAverager.getAverage(sumWeights);
			if (hypoDepthAverager.allSame && (float)hypoDepth == (float)(upper + 0.5*(lower-upper)) && !explicitHalfDepth)
				hypoDepth = Double.NaN;
			double length = lengthAverager.getAverage(sumWeights);
			double hypoDAS = dasAverager.getAverage(sumWeights);
			if (dasAverager.allSame && (float)hypoDAS == (float)(0.5*length) && !explicitHalfDAS)
				hypoDAS = Double.NaN;
			if (associatedFracts != null)
				for (int i=0; i<associatedFracts.length; i++)
					associatedFracts[i] /= sumWeights;
			return new GriddedRupture(ref.gridIndex, ref.location, ref.magnitude, rateAverager.getAverage(sumWeights),
					ref.rake, ref.dip, ref.strike, ref.strikeRange, upper, lower, length,
					hypoDepth, hypoDAS, ref.tectonicRegionType, associatedSectIDs, associatedFracts);
		}
	}
	
	private static class PropAverager {
		private double mySumWeight = 0d;
		private double weightedSum = 0d;
		private Double firstVal;
		private boolean allSame;
		
		public void add(double value, double weight) {
			if (firstVal == null) {
				firstVal = value;
				allSame = true;
			} else {
				allSame &= value == firstVal;
			}
			weightedSum += value*weight;
			mySumWeight += weight;
		}
		
		public double getAverage(double sumWeights) {
			if (allSame) {
				if ((float)sumWeights == (float)mySumWeight)
					return firstVal;
				else
					return firstVal * mySumWeight/sumWeights;
			}
			Preconditions.checkState(sumWeights > 0d);
			return weightedSum/sumWeights;
		}
	}
	
	public class Averager implements AveragingAccumulator<GridSourceProvider> {
		
		private GridSourceList ref = null;
		
		private GriddedRegion gridReg = null;
		private LocationList locs;
		private EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists;
		private EnumMap<TectonicRegionType, List<List<RupturePropertyAverager>>> trtRupturePropLists;
		private double totWeight = 0d;
		
//		private double totM5Sum = 0d;
		
		private boolean haveMultipleDepths;

		@Override
		public Class<GridSourceProvider> getType() {
			return GridSourceProvider.class;
		}

		@Override
		public void process(GridSourceProvider module, double relWeight) {
			Preconditions.checkState(module instanceof GridSourceList,
					"Can only average if all GridSourceProviders are of type GridSourceList");
			GridSourceList sourceList = (GridSourceList)module;
			if (trtRuptureLists == null) {
				ref = sourceList;
				// first time through, init
				trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
				trtRupturePropLists = new EnumMap<>(TectonicRegionType.class);
				Preconditions.checkState(totWeight == 0d, "Can't reuse averagers");
				this.gridReg = sourceList.getGriddedRegion();
				this.locs = sourceList.locs;
				
				// see if we have multiple ruptures with different depths; if so, we'll retain each unique rupture
				// across all branches including depth information, otherwise we'll average depths
				this.haveMultipleDepths = false;
				for (TectonicRegionType trt : sourceList.getTectonicRegionTypes()) {
					for (int gridIndex=0; !haveMultipleDepths && gridIndex<locs.size(); gridIndex++) {
						List<GriddedRupture> ruptures = sourceList.getRuptures(trt, gridIndex);
						if (!ruptures.isEmpty()) {
							ruptures = new ArrayList<>(ruptures);
							ruptures.sort(RUP_NON_AVERAGED_PROPS_COMPARATOR);
							for (int i=1; !haveMultipleDepths && i<ruptures.size(); i++)
								haveMultipleDepths = RUP_NON_AVERAGED_PROPS_COMPARATOR.compare(ruptures.get(i-1), ruptures.get(i)) == 0;
						}
					}
				}
				if (haveMultipleDepths)
					System.out.println("GridSourceList.Averager: we have multiple depths/lengths for otherwise unique ruptures; "
							+ "will not average any depth information");
			} else {
				// make sure they're identical
				Preconditions.checkState(locs.size() == sourceList.getNumLocations());
				for (int i=0; i<locs.size(); i++)
					Preconditions.checkState(LocationUtils.areSimilar(locs.get(i), sourceList.getLocation(i)));
			}
			
			for (TectonicRegionType trt : sourceList.getTectonicRegionTypes()) {
				List<List<GriddedRupture>> ruptureLists = trtRuptureLists.get(trt);
				List<List<RupturePropertyAverager>> rupturePropLists = trtRupturePropLists.get(trt);
				if (ruptureLists == null) {
					ruptureLists = new ArrayList<>(locs.size());
					rupturePropLists = new ArrayList<>(locs.size());
					for (int i=0; i<locs.size(); i++) {
						ruptureLists.add(null);
						rupturePropLists.add(null);
					}
					trtRuptureLists.put(trt, ruptureLists);
					trtRupturePropLists.put(trt, rupturePropLists);
				}
				for (int gridIndex=0; gridIndex<locs.size(); gridIndex++) {
					Preconditions.checkState(LocationUtils.areSimilar(ref.getLocation(gridIndex), sourceList.getLocation(gridIndex)));
					List<GriddedRupture> ruptures = sourceList.getRuptures(trt, gridIndex);
					if (!ruptures.isEmpty()) {
						List<GriddedRupture> ruptureList = ruptureLists.get(gridIndex);
						List<RupturePropertyAverager> ruptureProps = rupturePropLists.get(gridIndex);
						if (ruptureList == null) {
							ruptureList = new ArrayList<>();
							ruptureProps = new ArrayList<>();
							ruptureLists.set(gridIndex, ruptureList);
							rupturePropLists.set(gridIndex, ruptureProps);
						}
						for (GriddedRupture rupture : ruptures) {
//							if (rupture.magnitude >= 5d)
//								totM5Sum += rupture.rate*relWeight;
							int index = Collections.binarySearch(ruptureList, rupture,
									haveMultipleDepths ? RUP_FULL_PROPS_COMPARATOR : RUP_NON_AVERAGED_PROPS_COMPARATOR);
							RupturePropertyAverager props;
							if (index < 0) {
								// new, need to add it
								index = -(index + 1);
								ruptureList.add(index, rupture);
								props = new RupturePropertyAverager();
								ruptureProps.add(index, props);
							} else {
								// duplicate
								props = ruptureProps.get(index);
							}
							props.add(rupture, relWeight);
						}
						Preconditions.checkState(ruptureList.size() == ruptureProps.size());
					}
				}
			}
			
			totWeight += relWeight;
		}

		@Override
		public GridSourceProvider getAverage() {
			Preconditions.checkState(totWeight > 0d, "No weight assigned?");
//			System.out.println("Building average; totM5="+(float)(totM5Sum/totWeight));
			
			EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureListsOut = new EnumMap<>(TectonicRegionType.class);
//			double testRateM5 = 0d;
			
			for (TectonicRegionType trt : trtRuptureLists.keySet()) {
				List<List<GriddedRupture>> ruptureLists = trtRuptureLists.get(trt);
				List<List<RupturePropertyAverager>> rupturePropLists = trtRupturePropLists.get(trt);
				Preconditions.checkState(ruptureLists.size() == locs.size(),
						"rupList has %s, expected %s", ruptureLists.size(), locs.size());
				Preconditions.checkState(rupturePropLists.size() == locs.size(),
						"rupPropList has %s, expected %s", rupturePropLists.size(), locs.size());
				
				List<List<GriddedRupture>> ruptureListsOut = new ArrayList<>(locs.size());
				
				for (int gridIndex=0; gridIndex<locs.size(); gridIndex++) {
					if (ruptureLists.get(gridIndex) == null) {
						ruptureListsOut.add(null);
					} else {
						List<GriddedRupture> ruptureList = ruptureLists.get(gridIndex);
						List<RupturePropertyAverager> ruptureProps = rupturePropLists.get(gridIndex);
						Preconditions.checkState(ruptureList.size() == ruptureProps.size(),
								"rupList has %s, props has %s", ruptureList.size(), ruptureProps.size());
						List<GriddedRupture> ruptureListOut = new ArrayList<>(ruptureList.size());
						for (int i=0; i<ruptureList.size(); i++) {
							GriddedRupture rup = ruptureList.get(i);
							GriddedRupture modRup = ruptureProps.get(i).build(rup, totWeight);
//							if (modRup.magnitude >= 5d)
//								testRateM5 += modRup.rate;
							ruptureListOut.add(modRup);
						}
						ruptureListsOut.add(ruptureListOut);
						Preconditions.checkState(ruptureListOut.size() == ruptureList.size());
					}
				}
				Preconditions.checkState(ruptureListsOut.size() == locs.size());
				
				trtRuptureListsOut.put(trt, ruptureListsOut);
			}
			
//			System.out.println("Averaged totM5="+(float)testRateM5);
			
			GridSourceList ret = new GridSourceList();
			ret.setAll(gridReg, locs, trtRuptureListsOut);
			
			trtRuptureLists = null; // to prevent reuse
			return ret;
		}
		
	}
	
	public interface FiniteRuptureConverter {
		
		public GriddedRupture buildFiniteRupture(int gridIndex, Location loc, double magnitude, double rate,
				FocalMech focalMech, int[] associatedSections, double[] associatedSectionFracts);
	}
	
	public static GridSourceList convert(MFDGridSourceProvider mfdGridProv, FaultGridAssociations associations,
			FiniteRuptureConverter converter) {
		GriddedRegion gridReg = mfdGridProv.getGriddedRegion();
		List<List<GriddedRupture>> ruptureLists = new ArrayList<>(mfdGridProv.getNumLocations());
		for (int gridIndex=0; gridIndex<mfdGridProv.getNumLocations(); gridIndex++) {
			double fractSS = mfdGridProv.getFracStrikeSlip(gridIndex);
			double fractN = mfdGridProv.getFracNormal(gridIndex);
			double fractR = mfdGridProv.getFracReverse(gridIndex);
			
			IncrementalMagFreqDist mfd = mfdGridProv.getMFD(gridIndex);
			if (mfd == null) {
				ruptureLists.add(null);
				continue;
			}
			IncrementalMagFreqDist mfdAssoc = mfdGridProv.getMFD_SubSeisOnFault(gridIndex);
			Map<Integer, Double> nodeFractAssociations = null;
			if (mfdAssoc != null) {
				nodeFractAssociations = new HashMap<>(associations.getScaledSectFracsOnNode(gridIndex));
				Preconditions.checkState(!nodeFractAssociations.isEmpty());
				// turn it into a fractional: scale to 1 if not already
				double sumFracts = 0d;
				for (double fract : nodeFractAssociations.values())
					sumFracts += fract;
				if ((float)sumFracts != 1f) {
					for (int sectIndex : new ArrayList<>(nodeFractAssociations.keySet()))
						nodeFractAssociations.put(sectIndex, nodeFractAssociations.get(sectIndex)/sumFracts);
				}
			}
			List<GriddedRupture> ruptureList = new ArrayList<>();
			ruptureLists.add(ruptureList);
			for (int m=0; m<mfd.size(); m++) {
				double mag = mfd.getX(m);
				double totRate = mfd.getY(m);
				if (totRate == 0d)
					continue;
				double associatedFract = 0d;
				if (mfdAssoc != null && mfdAssoc.size() > m) {
					Preconditions.checkState((float)mfdAssoc.getX(m) == (float)mag);
					double assocRate = mfdAssoc.getY(mag);
					associatedFract = assocRate/totRate;
					Preconditions.checkState((float)associatedFract <= 1f, "Bad associatedFract = %s / %s = %s",
							assocRate, totRate, associatedFract);
				}
				for (FocalMech mech : FocalMech.values()) {
					double mechRate;
					switch (mech) {
					case STRIKE_SLIP:
						mechRate = totRate*fractSS;
						break;
					case NORMAL:
						mechRate = totRate*fractN;
						break;
					case REVERSE:
						mechRate = totRate*fractR;
						break;

					default:
						throw new IllegalStateException();
					}
					if (mechRate == 0d)
						continue;
					
					int[] associatedSections = null;
					double[] associatedSectionFracts = null;
					if (associatedFract > 0) {
						List<Integer> sectIndexes = new ArrayList<>(nodeFractAssociations.keySet());
						Collections.sort(sectIndexes);
						associatedSections = new int[sectIndexes.size()];
						associatedSectionFracts = new double[sectIndexes.size()];
						for (int s=0; s<sectIndexes.size(); s++) {
							int sectIndex = sectIndexes.get(s);
							associatedSections[s] = sectIndex;
							associatedSectionFracts[s] = associatedFract * nodeFractAssociations.get(sectIndex);
						}
					}
					ruptureList.add(converter.buildFiniteRupture(gridIndex, mfdGridProv.getLocation(gridIndex),
							mag, mechRate, mech, associatedSections, associatedSectionFracts));
				}
			}
		}
		
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
		trtRuptureLists.put(mfdGridProv.getTectonicRegionType(), ruptureLists);
		return new GridSourceList(gridReg, trtRuptureLists);
	}
	
	public static GridSourceList combine(GridSourceList... gridLists) {
		Preconditions.checkState(gridLists.length > 1);
		
		if (gridLists[0].gridReg != null) {
			// first try to do it using a combined region
			GriddedRegion unionGridReg = gridLists[0].getGriddedRegion();
			double latSpacing = unionGridReg.getLatSpacing();
			double lonSpacing = unionGridReg.getLonSpacing();
			Location anchor = unionGridReg.getLocation(0);
			List<String> unionedNames = new ArrayList<>();
			unionedNames.add(unionGridReg.getName());
			for (int i=1; unionGridReg != null && i<gridLists.length; i++) {
				GriddedRegion myReg = gridLists[i].getGriddedRegion();
				if (myReg == null) {
					System.err.println("WARNING: region "+i+" is null, won't use regions when combining");
					unionGridReg = null;
				} else if ((float)myReg.getLatSpacing() != (float)latSpacing
							|| (float)myReg.getLonSpacing() != (float)lonSpacing) {
					System.err.println("WARNING: region "+i+" ("+myReg.getName()+") has different spacing than previous region(s)");
					unionGridReg = null;
				} else {
					if (myReg.equals(unionGridReg))
						continue;
					// see if that region contains this one
					boolean fullyContained = true;
					for (Location loc : myReg.getNodeList()) {
						if (unionGridReg.indexForLocation(loc) < 0) {
							fullyContained = false;
							break;
						}
					}
					if (fullyContained)
						// no need to union, this one is already fully contained
						continue;
					Region unionReg = Region.union(unionGridReg, myReg);
					if (unionReg == null) {
						unionGridReg = null;
						System.err.println("WARNING: couldn't union region "+i+" ("+myReg.getName()+") with prior region(s): "
								+Joiner.on("; ").join(unionedNames));
						break;
					}
					unionedNames.add(myReg.getName());
					unionGridReg = new GriddedRegion(unionReg, latSpacing, lonSpacing, anchor);
				}
			}
			
			if (unionGridReg != null) {
				// might work, but make sure that we still contain all of the grid nodes
				boolean fullyContained = true;
				for (GridSourceList gridList : gridLists) {
					GriddedRegion myReg = gridList.getGriddedRegion();
					for (Location loc : myReg.getNodeList()) {
						if (unionGridReg.indexForLocation(loc) < 0) {
							fullyContained = false;
							break;
						}
					}
					if (!fullyContained)
						break;
				}
				if (fullyContained) {
					System.out.println("Building combined GridSourceList using stitched gridded region");
					return combine(unionGridReg, gridLists);
				}
				System.err.println("WARNING: built a stitched gridded region for all sub-regions but there's a gridding "
						+ "mismatch, will revert to just a location list");
			} else {
				System.err.println("WARNING: couldn't build a stitched gridded region for all sub-regions, will revert "
						+ "to just a location list");
			}
		}
		
		System.out.println("Building combined GridSourceList using a location list (no stitched region)");
		
		LocationList locs = new LocationList();
		Map<Location, Integer> locIndexMap = new HashMap<>();
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
		
		// first find each unique location
		int rawNumLocs = 0;
		for (GridSourceList gridList : gridLists) {
			for (int gridIndex=0; gridIndex<gridList.getNumLocations(); gridIndex++) {
				Location loc = gridList.getLocation(gridIndex);
				Integer index = locIndexMap.get(loc);
				rawNumLocs++;
				if (index == null) {
					index = locs.size();
					locs.add(loc);
					locIndexMap.put(loc, index);
				}
			}
		}
		System.out.println("Found "+locs.size()+" unique locations (out of "+rawNumLocs+" total)");
		
		for (GridSourceList gridList : gridLists) {
			for (TectonicRegionType trt : gridList.getTectonicRegionTypes()) {
				List<List<GriddedRupture>> ruptureLists = trtRuptureLists.get(trt);
				if (ruptureLists == null) {
					ruptureLists = new ArrayList<>(locs.size());
					for (int i=0; i<locs.size(); i++)
						ruptureLists.add(null);
					trtRuptureLists.put(trt, ruptureLists);
				}
				for (int gridIndex=0; gridIndex<gridList.getNumLocations(); gridIndex++) {
					ImmutableList<GriddedRupture> rups = gridList.getRuptures(trt, gridIndex);
					if (!rups.isEmpty()) {
						Location loc = gridList.getLocation(gridIndex);
						Integer mappedIndex = locIndexMap.get(loc);
						Preconditions.checkNotNull(mappedIndex,
								"Location %s is not mapped to a location in the combined location list?", loc);
						List<GriddedRupture> destRups = ruptureLists.get(mappedIndex);
						if (destRups == null) {
							destRups = new ArrayList<>(rups.size());
							ruptureLists.set(mappedIndex, destRups);
						}
						for (GriddedRupture rup : rups)
							destRups.add(rup.copyNewGridIndex(mappedIndex));
					}
				}
			}
		}
		
		return new GridSourceList(locs, trtRuptureLists);
	}
	
	public static GridSourceList combine(GriddedRegion combRegion, GridSourceList... gridLists) {
		EnumMap<TectonicRegionType, List<List<GriddedRupture>>> trtRuptureLists = new EnumMap<>(TectonicRegionType.class);
		
		for (GridSourceList gridList : gridLists) {
			for (TectonicRegionType trt : gridList.getTectonicRegionTypes()) {
				List<List<GriddedRupture>> ruptureLists = trtRuptureLists.get(trt);
				if (ruptureLists == null) {
					ruptureLists = new ArrayList<>(combRegion.getNodeCount());
					for (int i=0; i<combRegion.getNodeCount(); i++)
						ruptureLists.add(null);
					trtRuptureLists.put(trt, ruptureLists);
				}
				for (int gridIndex=0; gridIndex<gridList.getNumLocations(); gridIndex++) {
					ImmutableList<GriddedRupture> rups = gridList.getRuptures(trt, gridIndex);
					if (!rups.isEmpty()) {
						Location loc = gridList.getLocation(gridIndex);
						Preconditions.checkState(loc.equals(gridList.getGriddedRegion().getLocation(gridIndex)));
						int mappedGridIndex = combRegion.indexForLocation(loc);
						Preconditions.checkState(mappedGridIndex >= 0,
								"Location %s is not mapped to a location in the given combined gridded region", loc);
						List<GriddedRupture> destRups = ruptureLists.get(mappedGridIndex);
						if (destRups == null) {
							destRups = new ArrayList<>(rups.size());
							ruptureLists.set(mappedGridIndex, destRups);
						}
						for (GriddedRupture rup : rups)
							destRups.add(rup.copyNewGridIndex(mappedGridIndex));
					}
				}
			}
		}
		
		return new GridSourceList(combRegion, trtRuptureLists);
	}

}
