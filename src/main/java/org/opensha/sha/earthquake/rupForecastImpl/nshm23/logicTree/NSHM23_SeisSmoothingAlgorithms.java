package org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import org.opensha.commons.data.CSVFile;
import org.opensha.commons.data.xyz.GriddedGeoDataSet;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.logicTree.Affects;
import org.opensha.commons.logicTree.DoesNotAffect;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.commons.logicTree.LogicTreeNode;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.util.NSHM23_RegionLoader.SeismicityRegions;

import com.google.common.base.Preconditions;

@DoesNotAffect(FaultSystemRupSet.SECTS_FILE_NAME)
@DoesNotAffect(FaultSystemRupSet.RUP_SECTS_FILE_NAME)
@DoesNotAffect(FaultSystemRupSet.RUP_PROPS_FILE_NAME)
@DoesNotAffect(FaultSystemSolution.RATES_FILE_NAME)
@DoesNotAffect(GridSourceProvider.ARCHIVE_GRID_REGION_FILE_NAME)
@DoesNotAffect(GridSourceProvider.ARCHIVE_MECH_WEIGHT_FILE_NAME)
@Affects(GridSourceProvider.ARCHIVE_SUB_SEIS_FILE_NAME)
@Affects(GridSourceProvider.ARCHIVE_UNASSOCIATED_FILE_NAME)
public enum NSHM23_SeisSmoothingAlgorithms implements LogicTreeNode {
	
	ADAPTIVE("Adaptive Kernel", "Adaptive", 1d),
	FIXED("Fixed Kernel", "Fixed", 1d),
	AVERAGE("Average", "Average", 0d) {
		public GriddedGeoDataSet loadXYZ(SeismicityRegions region,
				NSHM23_DeclusteringAlgorithms declusteringAlg) throws IOException {
			List<GriddedGeoDataSet> xyzs = new ArrayList<>();
			List<Double> weights = new ArrayList<>();
			for (NSHM23_SeisSmoothingAlgorithms smooth : values()) {
				if (smooth.weight == 0d || smooth == this)
					continue;
				xyzs.add(smooth.loadXYZ(region, declusteringAlg));
				weights.add(smooth.weight);
			}
			return average(xyzs, weights);
		}
	};
	
	private String name;
	private String shortName;
	private double weight;

	private NSHM23_SeisSmoothingAlgorithms(String name, String shortName, double weight) {
		this.name = name;
		this.shortName = shortName;
		this.weight = weight;
	}
	
	private static final String NSHM23_SS_PATH_PREFIX = "/data/erf/nshm23/seismicity/spatial_seis_pdfs/";
	
	private String getResourceName(SeismicityRegions region, NSHM23_DeclusteringAlgorithms declusteringAlg) {
		return NSHM23_SS_PATH_PREFIX+region.name()+"/"+declusteringAlg.name()+"_"+name()+".csv";
	}
	
	private static GriddedGeoDataSet average(List<GriddedGeoDataSet> xyzs, List<Double> weights) {
		GriddedGeoDataSet avg = null;
		double sumWeight = 0d;
		for (int i=0; i<xyzs.size(); i++) {
			GriddedGeoDataSet xyz = xyzs.get(i);
			double weight = weights.get(i);
			if (avg == null)
				avg = new GriddedGeoDataSet(xyz.getRegion(), false);
			else
				Preconditions.checkState(avg.getRegion().equalsRegion(xyz.getRegion()));
			for (int j=0; j<xyz.size(); j++)
				avg.set(j, avg.get(j)+xyz.get(j)*weight);
			sumWeight += weight;
		}
		if (xyzs.size() == 1)
			return xyzs.get(0);
		avg.scale(1d/sumWeight);
		return avg;
	}
	
	public double[] load(SeismicityRegions region,
			NSHM23_DeclusteringAlgorithms declusteringAlg) throws IOException {
		return loadXYZ(region, declusteringAlg).getValues();
	}
	
	public GriddedGeoDataSet loadXYZ(SeismicityRegions region,
			NSHM23_DeclusteringAlgorithms declusteringAlg) throws IOException {
		if (declusteringAlg == NSHM23_DeclusteringAlgorithms.AVERAGE) {
			// average them
			List<GriddedGeoDataSet> xyzs = new ArrayList<>();
			List<Double> weights = new ArrayList<>();
			for (NSHM23_DeclusteringAlgorithms alg : NSHM23_DeclusteringAlgorithms.values()) {
				double weight = alg.getNodeWeight(null);
				if (weight == 0d || alg == NSHM23_DeclusteringAlgorithms.AVERAGE)
					continue;
				xyzs.add(loadXYZ(region, alg));
				weights.add(weight);
			}
			return average(xyzs, weights);
		}
		String resource = getResourceName(region, declusteringAlg);
		
		System.out.println("Loading spatial seismicity PDF from: "+resource);
		InputStream is = NSHM23_SeisSmoothingAlgorithms.class.getResourceAsStream(resource);
		Preconditions.checkNotNull(is, "Spatial seismicity PDF not found: %s", resource);
		CSVFile<String> csv = CSVFile.readStream(is, true);
		
		GriddedRegion gridReg = new GriddedRegion(region.load(), 0.1d, GriddedRegion.ANCHOR_0_0);
		GriddedGeoDataSet xyz = new GriddedGeoDataSet(gridReg, false);
		double sum = 0d;
		int numMapped = 0;
		for (int row=0; row<csv.getNumRows(); row++) {
			double lon = csv.getDouble(row, 0);
			double lat = csv.getDouble(row, 1);
			double val = csv.getDouble(row, 2);
			sum += val;
			Location loc = new Location(lat, lon);
			int gridIndex = gridReg.indexForLocation(loc);
			if (gridIndex >= 0) {
				numMapped++;
				Preconditions.checkState(xyz.get(gridIndex) == 0d);
				xyz.set(gridIndex, val);
			}
		}
		double sumMapped = xyz.getSumZ();
		System.out.println("totWeight="+(float)sum+";\tmappedWeight="+(float)sumMapped+"; mapping results:");
		System.out.println("\t"+numMapped+"/"+csv.getNumRows()+" ("
				+pDF.format((double)numMapped/(double)csv.getNumRows())+") of locations from input CSV mapped");
		System.out.println("\t"+numMapped+"/"+gridReg.getNodeCount()+" ("
				+pDF.format((double)numMapped/(double)gridReg.getNodeCount())+") of gridded region mapped");
		xyz.scale(1d/sumMapped);
		return xyz;
	}
	
	private static final DecimalFormat pDF = new DecimalFormat("0.00%");

	@Override
	public String getShortName() {
		return shortName;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
		return weight;
	}

	@Override
	public String getFilePrefix() {
		return name();
	}
	
	public static void main(String[] args) throws IOException {
		for (SeismicityRegions region : SeismicityRegions.values()) {
			if (region == SeismicityRegions.ALASKA || region == SeismicityRegions.CONUS_HAWAII)
				continue;
			for (NSHM23_SeisSmoothingAlgorithms smooth : values()) {
				for (NSHM23_DeclusteringAlgorithms alg : NSHM23_DeclusteringAlgorithms.values()) {
					System.out.println(region.name()+",\t"+smooth.name());
					smooth.load(region, alg);
				}
			}
		}
	}

}
