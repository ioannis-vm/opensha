package org.opensha.sha.earthquake.rupForecastImpl.nshm23.prior2018;

import java.util.ArrayList;
import java.util.List;

import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.commons.logicTree.LogicTreeLevel;
import org.opensha.commons.logicTree.LogicTreeNode;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_LogicTreeBranch;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_PaleoUncertainties;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_ScalingRelationships;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_SegmentationModels;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.NSHM23_SlipAlongRuptureModels;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.RupturePlausibilityModels;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.SegmentationMFD_Adjustment;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.ShawSegmentationModels;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.SubSectConstraintModels;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.SubSeisMoRateReductions;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.SupraSeisBValues;

import com.google.common.base.Preconditions;

import scratch.UCERF3.enumTreeBranches.SlipAlongRuptureModels;

/**
 * NSHM23 Logic Tree Branch implementation but using NSHM18 ingredients (FM, DM, Scaling)
 * 
 * @author kevin
 *
 */
public class NSHM18_LogicTreeBranch extends LogicTreeBranch<LogicTreeNode> {

	public static List<LogicTreeLevel<? extends LogicTreeNode>> levels;
	public static List<LogicTreeLevel<? extends LogicTreeNode>> levelsNewScale;

	// only U3-related ones here 
	public static LogicTreeLevel<NSHM18_FaultModels> FM =
			LogicTreeLevel.forEnum(NSHM18_FaultModels.class, "NSHM18 Fault Model", "FM");
	public static LogicTreeLevel<NSHM18_DeformationModels> DM =
			LogicTreeLevel.forEnum(NSHM18_DeformationModels.class, "NSHM18 Deformation Model", "DM");
	public static LogicTreeLevel<NSHM18_ScalingRels> SCALE =
			LogicTreeLevel.forEnum(NSHM18_ScalingRels.class, "Scaling Relationship", "Scale");
	
	static {
		levels = List.of(
				FM,
				NSHM23_LogicTreeBranch.PLAUSIBILITY,
				DM,
				SCALE,
				NSHM23_LogicTreeBranch.SLIP_ALONG,
				NSHM23_LogicTreeBranch.SUPRA_B,
				NSHM23_LogicTreeBranch.SUB_SECT_CONSTR,
				NSHM23_LogicTreeBranch.SUB_SEIS_MO,
				NSHM23_LogicTreeBranch.PALEO_UNCERT,
				NSHM23_LogicTreeBranch.SEG,
				NSHM23_LogicTreeBranch.SEG_ADJ);
		levelsNewScale = List.of(
				FM,
				NSHM23_LogicTreeBranch.PLAUSIBILITY,
				DM,
				NSHM23_LogicTreeBranch.SCALE,
				NSHM23_LogicTreeBranch.SLIP_ALONG,
				NSHM23_LogicTreeBranch.SUPRA_B,
				NSHM23_LogicTreeBranch.SUB_SECT_CONSTR,
				NSHM23_LogicTreeBranch.SUB_SEIS_MO,
				NSHM23_LogicTreeBranch.PALEO_UNCERT,
				NSHM23_LogicTreeBranch.SEG,
				NSHM23_LogicTreeBranch.SEG_ADJ);
	}
	
	/**
	 * This is the default reference branch
	 */
	public static final NSHM18_LogicTreeBranch DEFAULT = fromValues(levels,
			NSHM18_FaultModels.NSHM18_WUS_NoCA,
			RupturePlausibilityModels.COULOMB,
			NSHM18_DeformationModels.GEOL,
			NSHM18_ScalingRels.WC94_ML,
			NSHM23_SlipAlongRuptureModels.UNIFORM,
			SupraSeisBValues.B_0p5,
			SubSectConstraintModels.TOT_NUCL_RATE,
			SubSeisMoRateReductions.NONE,
			NSHM23_PaleoUncertainties.EVEN_FIT,
			NSHM23_SegmentationModels.MID,
			SegmentationMFD_Adjustment.REL_GR_THRESHOLD_AVG);
	
	/**
	 * This is the default reference branch using new scaling relationships
	 */
	public static final NSHM18_LogicTreeBranch DEFAULT_NEW_SCALE = fromValues(levelsNewScale,
			NSHM18_FaultModels.NSHM18_WUS_NoCA,
			RupturePlausibilityModels.COULOMB,
			NSHM18_DeformationModels.GEOL,
			NSHM23_ScalingRelationships.LOGA_C4p2,
			NSHM23_SlipAlongRuptureModels.UNIFORM,
			SupraSeisBValues.B_0p5,
			SubSectConstraintModels.TOT_NUCL_RATE,
			SubSeisMoRateReductions.NONE,
			NSHM23_PaleoUncertainties.EVEN_FIT,
			NSHM23_SegmentationModels.MID,
			SegmentationMFD_Adjustment.REL_GR_THRESHOLD_AVG);
	
	/**
	 * Creates a NSHM23LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from NSHM23LogicTreeBranch.DEFAULT).
	 * 
	 * @param levels
	 * @param vals
	 * @return
	 */
	public static NSHM18_LogicTreeBranch fromValues(List<LogicTreeLevel<? extends LogicTreeNode>> levels, List<LogicTreeNode> vals) {
		LogicTreeNode[] valsArray = new LogicTreeNode[vals.size()];
		
		for (int i=0; i<vals.size(); i++)
			valsArray[i] = vals.get(i);
		
		return fromValues(levels, valsArray);
	}
	
	/**
	 * Creates a NSHM23LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from NSHM23LogicTreeBranch.DEFAULT).
	 * 
	 * @param levels
	 * @param vals
	 * @return
	 */
	public static NSHM18_LogicTreeBranch fromValues(List<LogicTreeLevel<? extends LogicTreeNode>> levels, LogicTreeNode... vals) {
		return fromValues(levels, true, vals);
	}
	
	/**
	 * Creates a LogicTreeBranch instance from given set of node values. Null or missing values
	 * will be replaced with their default value (from LogicTreeBranch.DEFAULT) if setNullToDefault
	 * is true.
	 * 
	 * @param levels
	 * @param setNullToDefault if true, null or missing values will be set to their default value
	 * @param vals
	 * @return
	 */
	public static NSHM18_LogicTreeBranch fromValues(List<LogicTreeLevel<? extends LogicTreeNode>> levels,
			boolean setNullToDefault, LogicTreeNode... vals) {
		
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
		
		NSHM18_LogicTreeBranch branch = new NSHM18_LogicTreeBranch(levels, values);
		
		if (setNullToDefault) {
			for (int i=0; i<levels.size(); i++) {
				if (branch.getValue(i) == null)
					branch.setValue(i, DEFAULT.getValue(i));
			}
		}
		
		return branch;
	}
	
	@SuppressWarnings("unused") // used for deserialization
	public NSHM18_LogicTreeBranch(List<LogicTreeLevel<? extends LogicTreeNode>> levels) {
		super(levels);
	}
	
	public NSHM18_LogicTreeBranch(List<LogicTreeLevel<? extends LogicTreeNode>> levels, List<LogicTreeNode> values) {
		super(levels, values);
	}

}
