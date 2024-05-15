package org.opensha.sha.earthquake.rupForecastImpl.prvi25.logicTree;

import org.opensha.commons.calc.FaultMomentCalc;
import org.opensha.commons.eq.MagUtils;
import org.opensha.commons.logicTree.Affects;
import org.opensha.commons.logicTree.DoesNotAffect;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.RupSetScalingRelationship;

/**
 * NSHM23 scaling relationships, taken from:
 * 
 * Shaw, B.E. (2022, accepted), Magnitude and Slip Scaling Relations for Fault Based Seismic Hazard.
 * 
 * Current coefficient values from Bruce Shaw in person at 2022 SCEC meeting, decided to keep things equivalent to UCERF3
 * 
 * @author kevin
 *
 */
@DoesNotAffect(FaultSystemRupSet.SECTS_FILE_NAME)
@DoesNotAffect(FaultSystemRupSet.RUP_SECTS_FILE_NAME)
@Affects(FaultSystemRupSet.RUP_PROPS_FILE_NAME)
@Affects(FaultSystemSolution.RATES_FILE_NAME)
public enum PRVI25_SubductionScalingRelationships implements RupSetScalingRelationship {
	
	LOGA_C4p1("LogA+4.1", "LogA+4.1", "LogA_C4p1") {
		@Override
		public double getMag(double area, double length, double width, double origWidth, double aveRake) {
			area *= 1e-6; // m^2 -> km^2
			// eqn 1 with C=4.3
			return Math.log10(area) + 4.1;
		}

		@Override
		public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
			return 1d;
		}
	},
	LOGA_C4p0("LogA+4.0", "LogA+4.0", "LogA_C4p0") {
		@Override
		public double getMag(double area, double length, double width, double origWidth, double aveRake) {
			area *= 1e-6; // m^2 -> km^2
			// eqn 1 with C=4.2
			return Math.log10(area) + 4.0;
		}

		@Override
		public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
			return 1d;
		}
	},
	LOGA_C3p9("LogA+3.9", "LogA+3.9", "LogA_C3p9") {
		@Override
		public double getMag(double area, double length, double width, double origWidth, double aveRake) {
			area *= 1e-6; // m^2 -> km^2
			// eqn 1 with C=4.1
			return Math.log10(area) + 3.9;
		}

		@Override
		public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
			return 1d;
		}
	},
	AVERAGE("PRVI25 Subduction Average", "PRVI25-Sub-Avg", "PRVI_SubAvg") {
		@Override
		public double getMag(double area, double length, double width, double origWidth, double aveRake) {
			double sum = 0d;
			double sumWeights = 0d;
			for (PRVI25_SubductionScalingRelationships scale : values()) {
				double weight = scale.getNodeWeight(null);
				if (weight > 0d && scale != this) {
					sum += scale.getMag(area, length, width, origWidth, aveRake)*weight;
					sumWeights += weight;
				}
			}
			return sum/sumWeights;
		}

		@Override
		public double getAveSlip(double area, double length, double width, double origWidth, double aveRake) {
			double sum = 0d;
			double sumWeights = 0d;
			for (PRVI25_SubductionScalingRelationships scale : values()) {
				double weight = scale.getNodeWeight(null);
				if (weight > 0d && scale != this) {
					sum += scale.getAveSlip(area, length, width, origWidth, aveRake)*weight;
					sumWeights += weight;
				}
			}
			return sum/sumWeights;
		}

		@Override
		public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
			return 0d;
		}
	};
	
	private String name;
	private String shortName;
	private String filePrefix;

	private PRVI25_SubductionScalingRelationships(String name, String shortName, String filePrefix) {
		this.name = name;
		this.shortName = shortName;
		this.filePrefix = filePrefix;
	}

	@Override
	public String getFilePrefix() {
		return filePrefix;
	}

	@Override
	public String getShortName() {
		return shortName;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public double getAveSlip(double area, double length, double width, double origWidth, double aveRake) {
		double mag = getMag(area, length, width, origWidth, aveRake);
		double moment = MagUtils.magToMoment(mag);	// this returns: (Math.pow(10, 1.5 * magnitude + 9.05));
		return FaultMomentCalc.getSlip(area, moment);	// this returns: moment/(area*SHEAR_MODULUS);
	}

	@Override
	public abstract double getMag(double area, double length, double width, double origWidth, double aveRake);

}
