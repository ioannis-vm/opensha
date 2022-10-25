package org.opensha.sha.earthquake.rupForecastImpl.nshm23.gridded;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.commons.logicTree.LogicTreeNode;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider;
import org.opensha.sha.earthquake.faultSysSolution.reports.ReportPageGen;
import org.opensha.sha.earthquake.faultSysSolution.reports.plots.NucleationRatePlot;
import org.opensha.sha.earthquake.faultSysSolution.reports.plots.SolMFDPlot;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.NSHM23_InvConfigFactory;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_LogicTreeBranch;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_SeisSmoothingAlgorithms;
import org.opensha.sha.magdist.IncrementalMagFreqDist;

import com.google.common.base.Preconditions;

/**
 * {@link NSHM23_AbstractGridSourceProvider} instance that stitches together multiple {@link GridSourceProvider}
 * instances. Each seismicity region has its own MFD and spatial seismicity PDF, but a {@link FaultSystemSolution} may
 * span multiple regions. This class can be used to combine them into a single
 * {@link NSHM23_AbstractGridSourceProvider} for the model region.
 * 
 * @author kevin
 *
 */
public class NSHM23_CombinedRegionGridSourceProvider extends NSHM23_AbstractGridSourceProvider {
	
	private GriddedRegion gridReg;
	private List<NSHM23_SingleRegionGridSourceProvider> regionalProviders;
	private NSHM23_FaultCubeAssociations combinedFaultCubeAssociations;
	
	// mapping of grid node indexes to grid source providers
	private GridSourceProvider[] nodeGridProvs;
	// mapping of grid node indexes to the corresponding index in the source provider
	private int[] nodeGridIndexes;

	public NSHM23_CombinedRegionGridSourceProvider(FaultSystemSolution sol, GriddedRegion gridReg,
			List<NSHM23_SingleRegionGridSourceProvider> regionalProviders) {
		this(sol, buildCombinedFaultCubeAssociations(sol.getRupSet(), gridReg, regionalProviders), regionalProviders);
	}
	
	private static NSHM23_FaultCubeAssociations buildCombinedFaultCubeAssociations(
			FaultSystemRupSet rupSet, GriddedRegion gridReg,
			List<NSHM23_SingleRegionGridSourceProvider> regionalProviders) {
		List<NSHM23_FaultCubeAssociations> regionalAssociations = new ArrayList<>();
		for (NSHM23_SingleRegionGridSourceProvider prov : regionalProviders)
			regionalAssociations.add(prov.getFaultCubeassociations());
		return new NSHM23_FaultCubeAssociations(rupSet, new CubedGriddedRegion(gridReg), regionalAssociations);
	}

	public NSHM23_CombinedRegionGridSourceProvider(FaultSystemSolution sol,
			NSHM23_FaultCubeAssociations combinedFaultCubeAssociations,
			List<NSHM23_SingleRegionGridSourceProvider> regionalProviders) {
		this.combinedFaultCubeAssociations = combinedFaultCubeAssociations;
		this.gridReg = combinedFaultCubeAssociations.getRegion();
		this.regionalProviders = regionalProviders;
		nodeGridProvs = new GridSourceProvider[gridReg.getNodeCount()];
		nodeGridIndexes = new int[nodeGridProvs.length];
		int numMapped = 0;
		for (int gridIndex=0; gridIndex<nodeGridProvs.length; gridIndex++) {
			Location loc = gridReg.locationForIndex(gridIndex);
			GridSourceProvider match = null;
			int matchIndex = -1;
			for (GridSourceProvider prov : regionalProviders) {
				int myIndex = prov.getGriddedRegion().indexForLocation(loc);
				if (myIndex >= 0) {
					Preconditions.checkState(match == null,
							"TODO: don't yet support grid locations that map to multiple sub-regions");
					match = prov;
					matchIndex = myIndex;
				}
			}
			if (match != null) {
				numMapped++;
				nodeGridProvs[gridIndex] = match;
				nodeGridIndexes[gridIndex] = matchIndex;
			} else {
				nodeGridIndexes[gridIndex] = -1;
			}
		}
		System.out.println("Mapped "+numMapped+"/"+nodeGridIndexes.length
				+" model region grid locations to sub-region grid locations");
	}
	
	@Override
	public NSHM23_FaultCubeAssociations getFaultCubeassociations() {
		return combinedFaultCubeAssociations;
	}

	@Override
	public IncrementalMagFreqDist getMFD_Unassociated(int gridIndex) {
		if (nodeGridProvs[gridIndex] != null)
			return nodeGridProvs[gridIndex].getMFD_Unassociated(nodeGridIndexes[gridIndex]);
		return null;
	}

	@Override
	public IncrementalMagFreqDist getMFD_SubSeisOnFault(int gridIndex) {
		if (nodeGridProvs[gridIndex] != null)
			return nodeGridProvs[gridIndex].getMFD_SubSeisOnFault(nodeGridIndexes[gridIndex]);
		return null;
	}

	@Override
	public GriddedRegion getGriddedRegion() {
		return gridReg;
	}

	@Override
	public double getFracStrikeSlip(int gridIndex) {
		if (nodeGridProvs[gridIndex] != null)
			return nodeGridProvs[gridIndex].getFracStrikeSlip(nodeGridIndexes[gridIndex]);
		return 0d;
	}

	@Override
	public double getFracReverse(int gridIndex) {
		if (nodeGridProvs[gridIndex] != null)
			return nodeGridProvs[gridIndex].getFracReverse(nodeGridIndexes[gridIndex]);
		return 0d;
	}

	@Override
	public double getFracNormal(int gridIndex) {
		if (nodeGridProvs[gridIndex] != null)
			return nodeGridProvs[gridIndex].getFracNormal(nodeGridIndexes[gridIndex]);
		return 0d;
	}

	@Override
	public void scaleAllMFDs(double[] valuesArray) {
		Preconditions.checkState(valuesArray.length == size());
		for (GridSourceProvider prov : regionalProviders) {
			GriddedRegion provReg = prov.getGriddedRegion();
			double[] scalars = new double[provReg.getNumLocations()];
			// init to all 1's
			for (int i=0; i<scalars.length; i++)
				scalars[i] = 1;
			// now figure out what maps to this
			boolean anyMapped = false;
			for (int gridIndex=0; gridIndex<valuesArray.length; gridIndex++) {
				if (valuesArray[gridIndex] != 1d && nodeGridProvs[gridIndex] == prov) {
					// it's a match
					anyMapped = true;
					scalars[nodeGridIndexes[gridIndex]] = scalars[gridIndex];
				}
			}
			if (anyMapped)
				prov.scaleAllMFDs(scalars);
		}
	}
	
	public static void main(String[] args) throws IOException {
		FaultSystemSolution sol = FaultSystemSolution.load(new File("/home/kevin/OpenSHA/UCERF4/batch_inversions/"
				+ "2022_08_22-nshm23_branches-NSHM23_v2-CoulombRupSet-TotNuclRate-NoRed-ThreshAvgIterRelGR/"
				+ "results_NSHM23_v2_CoulombRupSet_branch_averaged.zip"));
		
		LogicTreeBranch<LogicTreeNode> offFaultBranch = NSHM23_LogicTreeBranch.DEFAULT_COMBINED.copy();
		offFaultBranch.setValue(NSHM23_SeisSmoothingAlgorithms.AVERAGE);
		
		NSHM23_AbstractGridSourceProvider gridProv = NSHM23_InvConfigFactory.buildGridSourceProv(sol, offFaultBranch);
		
		sol.addModule(gridProv);
		sol.getRupSet().addModule(gridProv.getFaultCubeassociations());
		sol.write(new File("/tmp/ba_with_grid_seis.zip"));
		
		sol.addModule(gridProv);
		ReportPageGen pageGen = new ReportPageGen(sol.getRupSet(), sol, "Solution", new File("/tmp/report"),
				List.of(new SolMFDPlot(), new NucleationRatePlot()));
		pageGen.setReplot(true);
		pageGen.generatePage();
	}

}
