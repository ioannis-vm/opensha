package org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.commons.logicTree.LogicTreeLevel;
import org.opensha.commons.logicTree.LogicTreeLevel.EnumBackedLevel;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider;
import org.opensha.commons.logicTree.LogicTreeNode;

import com.google.common.base.Preconditions;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.enumTreeBranches.MaxMagOffFault;
import scratch.UCERF3.enumTreeBranches.ScalingRelationships;
import scratch.UCERF3.enumTreeBranches.SlipAlongRuptureModels;
import scratch.UCERF3.enumTreeBranches.SpatialSeisPDF;
import scratch.UCERF3.enumTreeBranches.TotalMag5Rate;

/**
 * NSHM23 Logic Tree Branch implementation but using UCERF3 ingredients (FM, DM, Scaling)
 * 
 * @author kevin
 *
 */
public class NSHM23_U3_HybridLogicTreeBranch extends LogicTreeBranch<LogicTreeNode> {

	public static List<LogicTreeLevel<? extends LogicTreeNode>> levels;
	public static List<LogicTreeLevel<? extends LogicTreeNode>> levelsMaxDist;
	public static List<LogicTreeLevel<? extends LogicTreeNode>> levelsOffFault;

	// only U3-related ones here 
	public static LogicTreeLevel<FaultModels> U3_FM =
			LogicTreeLevel.forEnum(FaultModels.class, "UCERF3 Fault Model", "FM");
	public static LogicTreeLevel<U3_UncertAddDeformationModels> U3_WRAPPED_DM =
			LogicTreeLevel.forEnum(U3_UncertAddDeformationModels.class, "UCERF3 Deformation Model", "DM");
	public static LogicTreeLevel<ScalingRelationships> SCALE =
			LogicTreeLevel.forEnum(ScalingRelationships.class, "Scaling Relationship", "Scale");
	public static LogicTreeLevel<SlipAlongRuptureModels> SLIP_ALONG =
			LogicTreeLevel.forEnum(SlipAlongRuptureModels.class, "Slip Along Rupture", "SlipAlong");
//	public static LogicTreeLevel<ShawSegmentationModels> SEG =
//			LogicTreeLevel.forEnum(ShawSegmentationModels.class, "Segmentation Model", "SegModel");
//	public static LogicTreeLevel<DistDependSegShift> SEG_SHIFT =
//			LogicTreeLevel.forEnum(DistDependSegShift.class, "Dist-Dependent Seg Model Shift", "SegShift");
	
	/*
	 * Gridded seismicity branch levels
	 */
	public static EnumBackedLevel<TotalMag5Rate> SEIS_RATE =
			LogicTreeLevel.forEnum(TotalMag5Rate.class, "U3 Regional Seismicity Rate", "SeisRate");
	public static EnumBackedLevel<SpatialSeisPDF> SEIS_PDF =
			LogicTreeLevel.forEnum(SpatialSeisPDF.class, "U3 Spatial Seismicity PDF", "SpatSeisPDF");
	public static EnumBackedLevel<MaxMagOffFault> MMAX_OFF =
			LogicTreeLevel.forEnum(MaxMagOffFault.class, "U3 Off Fault Mmax", "MmaxOff");
	
	static {
		// exhaustive for now, can trim down later
		levels = List.of(U3_FM, NSHM23_LogicTreeBranch.PLAUSIBILITY, U3_WRAPPED_DM, SCALE, SLIP_ALONG,
				NSHM23_LogicTreeBranch.SUPRA_B, NSHM23_LogicTreeBranch.SUB_SECT_CONSTR,
				NSHM23_LogicTreeBranch.SUB_SEIS_MO, NSHM23_LogicTreeBranch.PALEO_UNCERT, NSHM23_LogicTreeBranch.SEG,
				NSHM23_LogicTreeBranch.SEG_ADJ);
		levelsMaxDist = List.of(U3_FM, NSHM23_LogicTreeBranch.PLAUSIBILITY, U3_WRAPPED_DM, SCALE, SLIP_ALONG,
				NSHM23_LogicTreeBranch.SUPRA_B, NSHM23_LogicTreeBranch.SUB_SECT_CONSTR,
				NSHM23_LogicTreeBranch.SUB_SEIS_MO, NSHM23_LogicTreeBranch.PALEO_UNCERT, NSHM23_LogicTreeBranch.MAX_DIST,
				NSHM23_LogicTreeBranch.RUPS_THROUGH_CREEPING);
		levelsOffFault = List.of(SEIS_RATE, SEIS_PDF, MMAX_OFF);
		
		// need to modify the gridded seismicity branch levels to indicate that they are decoupled from the supra-seis
		// rate model (unlike in UCERF3)
		Collection<String> gridSeisAffected = NSHM23_LogicTreeBranch.SEIS_RATE.getAffected();
		Collection<String> gridSeisUnaffected = NSHM23_LogicTreeBranch.SEIS_RATE.getNotAffected();
		SEIS_RATE.setAffected(gridSeisAffected, gridSeisUnaffected, false);
		SEIS_PDF.setAffected(gridSeisAffected, gridSeisUnaffected, false);
		MMAX_OFF.setAffected(gridSeisAffected, gridSeisUnaffected, false);
	}
	
	/**
	 * This is the default reference branch
	 */
	public static final NSHM23_U3_HybridLogicTreeBranch DEFAULT = fromValues(FaultModels.FM3_1,
			RupturePlausibilityModels.COULOMB, U3_UncertAddDeformationModels.U3_ZENG, ScalingRelationships.SHAW_2009_MOD,
			SlipAlongRuptureModels.UNIFORM, SupraSeisBValues.B_0p5, SubSectConstraintModels.TOT_NUCL_RATE,
			SubSeisMoRateReductions.SUB_B_1, NSHM23_PaleoUncertainties.EVEN_FIT, NSHM23_SegmentationModels.MID,
			SegmentationMFD_Adjustment.REL_GR_THRESHOLD_AVG);
	
	/**
	 * Creates a NSHM23LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from NSHM23LogicTreeBranch.DEFAULT).
	 * 
	 * @param vals
	 * @return
	 */
	public static NSHM23_U3_HybridLogicTreeBranch fromValues(List<LogicTreeNode> vals) {
		LogicTreeNode[] valsArray = new LogicTreeNode[vals.size()];
		
		for (int i=0; i<vals.size(); i++)
			valsArray[i] = vals.get(i);
		
		return fromValues(valsArray);
	}
	
	/**
	 * Creates a NSHM23LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from NSHM23LogicTreeBranch.DEFAULT).
	 * 
	 * @param vals
	 * @return
	 */
	public static NSHM23_U3_HybridLogicTreeBranch fromValues(LogicTreeNode... vals) {
		return fromValues(true, vals);
	}
	
	/**
	 * Creates a LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from LogicTreeBranch.DEFAULT) if setNullToDefault
	 * is true.
	 * 
	 * @param setNullToDefault if true, null or missing values will be set to their default value
	 * @param vals
	 * @return
	 */
	public static NSHM23_U3_HybridLogicTreeBranch fromValues(boolean setNullToDefault, LogicTreeNode... vals) {
		
		// initialize branch with null
		List<LogicTreeNode> values = new ArrayList<>();
		for (int i=0; i<levels.size(); i++)
			values.add(null);
		
		// now add each value
		for (LogicTreeNode val : vals) {
			if (val == null)
				continue;
			
			int ind = -1;
			for (int i=0; i<levels.size(); i++) {
				LogicTreeLevel<?> level = levels.get(i);
				if (level.isMember(val)) {
					ind = i;
					break;
				}
			}
			Preconditions.checkArgument(ind >= 0, "Value of class '"+val.getClass()+"' does not match any known branch level");
			values.set(ind, val);
		}
		
		NSHM23_U3_HybridLogicTreeBranch branch = new NSHM23_U3_HybridLogicTreeBranch(values);
		
		if (setNullToDefault) {
			for (int i=0; i<levels.size(); i++) {
				if (branch.getValue(i) == null)
					branch.setValue(i, DEFAULT.getValue(i));
			}
		}
		
		return branch;
	}
	
	@SuppressWarnings("unused") // used for deserialization
	public NSHM23_U3_HybridLogicTreeBranch() {
		super(levels);
	}
	
	public NSHM23_U3_HybridLogicTreeBranch(List<LogicTreeNode> values) {
		super(levels, values);
	}

}
