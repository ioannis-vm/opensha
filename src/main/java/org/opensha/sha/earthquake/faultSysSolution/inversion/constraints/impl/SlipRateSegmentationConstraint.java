package org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensha.commons.data.function.EvenlyDiscretizedFunc;
import org.opensha.commons.data.function.HistogramFunction;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.ConstraintWeightingType;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.InversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.modules.AveSlipModule;
import org.opensha.sha.earthquake.faultSysSolution.modules.ClusterRuptures;
import org.opensha.sha.earthquake.faultSysSolution.modules.SectSlipRates;
import org.opensha.sha.earthquake.faultSysSolution.modules.SlipAlongRuptureModel;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityConfiguration;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.Shaw07JumpDistProb;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import cern.colt.matrix.tdouble.DoubleMatrix2D;

/**
 * Slip-rate segmentation constraint. Applies rupture segmentation as a fractional slip-rate constraint between faults.
 * The segmentation model can theoretically take any parameters, but the first implementation is the Shaw (2007) distance
 * model. A {@link RateCombiner} decides how quantities are combined across either side of the jump, for example,
 * one might want to base this constraint on the lower of the two slip rates (or the average).
 * <br>
 * This constraint can be applied either as an equality constraint (will try to force connections to exactly match the
 * segmentation rate) or as an inequality constraint (will try to force connections not to exceed the segmentation rate).
 * <br>
 * It can also be applied as a net constraint, where the segmentation model will be fit on average, allowing for individual
 * junctions to vary as the inversion sees fit.
 * <br>
 * This constraint is generally not recommended, it has been superceded by {@link JumpProbabilityConstraint} that was
 * used in NSHM23.
 * 
 * @author Kevin Milner and Ned Field
 *
 */
public class SlipRateSegmentationConstraint extends InversionConstraint {
	
	private SegmentationModel segModel;
	private RateCombiner combiner;
	private boolean netConstraint;
	private boolean includeUnusedInNet;
	
	private transient FaultSystemRupSet rupSet;
	/*
	 *  map from jumps to rupture IDs that use that jump. Within the jump, fromID will always be < toID
	 */
	private transient Map<Jump, List<Integer>> jumpRupturesMap;
	private transient EvenlyDiscretizedFunc scalarBins;

	@JsonAdapter(SlipRateSegmentationConstraint.SegmentationModelAdapter.class)
	public static interface SegmentationModel {
		public double calcReductionBetween(Jump jump);
	}
	
	public static interface ScalarSegmentationModel extends SegmentationModel {
		public default double calcReductionBetween(Jump jump) {
			return calcReductionForScalar(getScalar(jump));
		}
		public double calcReductionForScalar(double scalar);
		public double getScalar(Jump jump);
		public EvenlyDiscretizedFunc getScalarBins(double minVal, double maxVal);
	}
	
	public static class Shaw07JumpDistSegModel implements ScalarSegmentationModel {
		
		private double a;
		private double r0;

		public Shaw07JumpDistSegModel(double a, double r0) {
			this.a = a;
			this.r0 = r0;
		}

		@Override
		public double calcReductionForScalar(double distance) {
			return Shaw07JumpDistProb.calcJumpProbability(distance, a, r0);
		}

		@Override
		public double getScalar(Jump jump) {
			return jump.distance;
		}

		@Override
		public EvenlyDiscretizedFunc getScalarBins(double minVal, double maxVal) {
			return HistogramFunction.getEncompassingHistogram(0.01, Math.max(maxVal, 1d), 0.5);
		}
		
	}
	
	public static class SegmentationModelAdapter extends TypeAdapter<SegmentationModel> {
		
		Gson gson = new Gson();

		@Override
		public void write(JsonWriter out, SegmentationModel value) throws IOException {
			out.beginObject();
			
			out.name("type").value(value.getClass().getName());
			out.name("data");
			gson.toJson(value, value.getClass(), out);
			
			out.endObject();
		}

		@SuppressWarnings("unchecked")
		@Override
		public SegmentationModel read(JsonReader in) throws IOException {
			Class<? extends SegmentationModel> type = null;
			
			in.beginObject();
			
			Preconditions.checkState(in.nextName().equals("type"), "JSON 'type' object must be first");
			try {
				type = (Class<? extends SegmentationModel>) Class.forName(in.nextString());
			} catch (ClassNotFoundException | ClassCastException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
			
			Preconditions.checkState(in.nextName().equals("data"), "JSON 'data' object must be second");
			SegmentationModel model = gson.fromJson(in, type);
			
			in.endObject();
			return model;
		}
		
	}
	
	public enum RateCombiner {
		MIN("Min Rate") {
			@Override
			public double combine(double rate1, double rate2) {
				return Math.min(rate1, rate2);
			}
		},
		MAX("Max Rate") {
			@Override
			public double combine(double rate1, double rate2) {
				return Math.max(rate1, rate2);
			}
		},
		AVERAGE("Avg Rate") {
			@Override
			public double combine(double rate1, double rate2) {
				return 0.5*(rate1 + rate2);
			}
		};
		
		private String label;

		private RateCombiner(String label) {
			this.label = label;
		}
	
		@Override
		public String toString() {
			return label;
		}
		
		public abstract double combine(double rate1, double rate2);
	}
	
	/**
	 * 
	 * 
	 * @param rupSet rupture set, must have average slip, slip along rupture, and slip rate data attached
	 * @param segModel segmentation model to use, e.g., Shaw '07
	 * @param combiner method for combining rates across each side of each jump
	 * @param weight constraint weight
	 * @param normalized if true, constraint will be normalized to match low-slip rate faults equally well
	 * @param inequality false for equality (exactly match seg. rate), true for inequality (don't exceed seg. rate)
	 */
	public SlipRateSegmentationConstraint(FaultSystemRupSet rupSet, SegmentationModel segModel,
			RateCombiner combiner, double weight, boolean normalized, boolean inequality) {
		this(rupSet, segModel, combiner, weight, normalized, inequality, false, false);
	}
	
	/**
	 * 
	 * 
	 * @param rupSet rupture set, must have average slip, slip along rupture, and slip rate data attached
	 * @param segModel segmentation model to use, e.g., Shaw '07
	 * @param combiner method for combining rates across each side of each jump
	 * @param weight constraint weight
	 * @param normalized if true, constraint will be normalized to match low-slip rate faults equally well
	 * @param inequality false for equality (exactly match seg. rate), true for inequality (don't exceed seg. rate)
	 * @param netConstraint if true, will be applied as a binned net constraint to match everything on average in each bin
	 * @param includeUnusedInNet if true, unused connections (from the connection strategy) will also be counted in 
	 */
	public SlipRateSegmentationConstraint(FaultSystemRupSet rupSet, SegmentationModel segModel,
			RateCombiner combiner, double weight, boolean normalized, boolean inequality,
			boolean netConstraint, boolean includeUnusedInNet) {
		super(getName(normalized, netConstraint), getShortName(normalized, netConstraint), weight, inequality,
				normalized ? ConstraintWeightingType.NORMALIZED : ConstraintWeightingType.UNNORMALIZED);
		this.segModel = segModel;
		this.combiner = combiner;
		this.netConstraint = netConstraint;
		this.includeUnusedInNet = includeUnusedInNet;
		
		if (netConstraint) {
			Preconditions.checkState(normalized, "Net constraints must be normalized");
			Preconditions.checkState(segModel instanceof ScalarSegmentationModel,
					"Must be a scalar segmentation model for net constraint");
		}
		setRuptureSet(rupSet);
	}

	private static String getShortName(boolean normalized, boolean netConstraint) {
		if (netConstraint)
			return "NetSlipSeg";
		if (normalized)
			return "NormSlipSeg";
		return "SlipSeg";
	}

	private static String getName(boolean normalized, boolean netConstraint) {
		if (netConstraint)
			return "Net (Distance-Binned) Slip Rate Segmentation";
		if (normalized)
			return "Normalized Slip Rate Segmentation";
		return "Slip Rate Segmentation";
	}

	@Override
	public int getNumRows() {
		checkInitJumpRups();
		if (netConstraint)
			// one row for each distance bin
			return scalarBins.size();
		// one row for each unique section-to-section connection
		return jumpRupturesMap.size();
	}

	@Override
	public long encode(DoubleMatrix2D A, double[] d, int startRow) {
		checkInitJumpRups();
		int row = startRow;
		
		List<Jump> jumps = new ArrayList<>(jumpRupturesMap.keySet());
		Collections.sort(jumps, Jump.id_comparator); // sort jumps for consistent row ordering
		
		AveSlipModule aveSlips = rupSet.requireModule(AveSlipModule.class);
		SlipAlongRuptureModel slipAlongModel = rupSet.requireModule(SlipAlongRuptureModel.class);
		SectSlipRates slipRates = rupSet.requireModule(SectSlipRates.class);
		
		Preconditions.checkState(weightingType == ConstraintWeightingType.NORMALIZED
				|| weightingType == ConstraintWeightingType.UNNORMALIZED,
				"Only normalized and un-normalized weighting types are supported");
		boolean normalized = weightingType == ConstraintWeightingType.NORMALIZED;
		
		Map<Jump, Integer> jumpScalarBinIndexes = null;
		double[] scalarBinAMults = null;
		double[] scalarBinTargets = null;
		ScalarSegmentationModel scalarSeg;
		if (netConstraint) {
			Preconditions.checkState(normalized, "Net constraint must be normalized");
			Preconditions.checkState(segModel instanceof ScalarSegmentationModel,
					"Must be a scalar segmentation model for net constraint");
			scalarSeg = (ScalarSegmentationModel)segModel;
			jumpScalarBinIndexes = new HashMap<>();

			scalarBinAMults = new double[scalarBins.size()];
			scalarBinTargets = new double[scalarBins.size()];
			for (Jump jump : jumps) {
				double scalar = scalarSeg.getScalar(jump);
				int bin = scalarBins.getClosestXIndex(scalar);
				scalarBinAMults[bin]++;
				scalarBinTargets[bin] += scalarSeg.calcReductionForScalar(scalar);
				jumpScalarBinIndexes.put(jump, bin);
			}
			for (int i=0; i<scalarBinAMults.length; i++) {
				scalarBinAMults[i] = 1d/scalarBinAMults[i];
				scalarBinTargets[i] *= scalarBinAMults[i];
			}
		}
		
		long count = 0;
		
		for (Jump jump : jumps) {
			int fromID = jump.fromSection.getSectionId();
			int toID = jump.toSection.getSectionId();
			
			double rate1 = slipRates.getSlipRate(fromID);
			double rate2 = slipRates.getSlipRate(toID);
			// this is the combined slip rate across both sections (e.g., the lower of the two, avg, etc)
			double combRate = combiner.combine(rate1, rate2);
			Preconditions.checkState(Double.isFinite(combRate), "Non-finite combined slip-rate: %s", combRate);
			double segFract = segModel.calcReductionBetween(jump);
			Preconditions.checkState(Double.isFinite(segFract) && segFract >= 0d && segFract <= 1d,
					"Bad segmentation fraction: %s", segFract);
			// apply the segmentation model as a fraction of that combined rate
			double segRate = combRate * segFract;
			Preconditions.checkState(Double.isFinite(segRate), "Non-finite segmentation rate: %s", segRate);
			
			int bin = -1;
			if (netConstraint) {
				bin = jumpScalarBinIndexes.get(jump);
				row = startRow + bin;
			}
			
			for (int rup : jumpRupturesMap.get(jump)) {
				double[] slips = slipAlongModel.calcSlipOnSectionsForRup(rupSet, aveSlips, rup);
				List<Integer> sects = rupSet.getSectionsIndicesForRup(rup);
				
				double slip1 = Double.NaN;
				double slip2 = Double.NaN;
				for (int i=0; i<slips.length; i++) {
					int sect = sects.get(i);
					if (sect == fromID)
						slip1 = slips[i];
					else if (sect == toID)
						slip2 = slips[i];
				}
				
				// combine the rupture slip on either side of the jump
				double avgSlip = combiner.combine(slip1, slip2);
				Preconditions.checkState(Double.isFinite(avgSlip),
						"Non-finite average slip across jump: %s (from %s and %s)", avgSlip, slip1, slip2);
				if (netConstraint) {
					double prev = getA(A, row, rup);
					setA(A, row, rup, prev + weight*scalarBinAMults[bin]*avgSlip/combRate);
					if (prev == 0d)
						count++;
				} else {
					if (normalized)
						setA(A, row, rup, weight*avgSlip/combRate);
					else
						setA(A, row, rup, weight*avgSlip);
					count++;
				}
			}
			
			if (netConstraint)
				d[row] = weight*scalarBinTargets[bin];
			else if (normalized)
				d[row] = weight*segFract;
			else
				d[row] = weight*segRate;
			
			row++;
		}
		return count;
	}
	
	private synchronized void checkInitJumpRups() {
		if (jumpRupturesMap == null) {
			System.out.println("Detecting jumps for segmentation constraint");
			jumpRupturesMap = new HashMap<>();
			
			ClusterRuptures cRups = rupSet.requireModule(ClusterRuptures.class);
			int jumpingRups = 0;
			for (int r=0; r<cRups.size(); r++) {
				ClusterRupture rup = cRups.get(r);
//				System.out.println("Rupture "+r+": "+rup);
				boolean hasJumps = false;
				for (Jump jump : rup.getJumpsIterable()) {
					if (jump.fromSection.getSectionId() > jump.toSection.getSectionId())
						jump = jump.reverse();
					List<Integer> jumpRups = jumpRupturesMap.get(jump);
					if (jumpRups == null) {
						jumpRups = new ArrayList<>();
						jumpRupturesMap.put(jump, jumpRups);
					}
					jumpRups.add(r);
					hasJumps = true;
				}
				if (hasJumps)
					jumpingRups++;
			}
			System.out.println("Found "+jumpRupturesMap.size()+" unique jumps, involving "+jumpingRups+" ruptures");
			if (netConstraint) {
				if (includeUnusedInNet) {
					ClusterConnectionStrategy connStrat = rupSet.requireModule(PlausibilityConfiguration.class).getConnectionStrategy();
					int unusedCount = 0;
					for (Jump jump : connStrat.getAllPossibleJumps()) {
						if (jump.fromSection.getSectionId() > jump.toSection.getSectionId())
							jump = jump.reverse();
						if (!jumpRupturesMap.containsKey(jump)) {
							jumpRupturesMap.put(jump, new ArrayList<>());
							unusedCount++;
						}
					}
					System.out.println("Added "+unusedCount+" additional jumps from connection strategy that are never used");
				}
				
				ScalarSegmentationModel scalarSeg = (ScalarSegmentationModel)segModel;
				
				double minVal = Double.POSITIVE_INFINITY;
				double maxVal = Double.NEGATIVE_INFINITY;
				for (Jump jump : jumpRupturesMap.keySet()) {
					double val = scalarSeg.getScalar(jump);
					minVal = Math.min(minVal, val);
					maxVal = Math.max(maxVal, val);
				}
				
				scalarBins = scalarSeg.getScalarBins(minVal, maxVal);
			}
		}
	}

	@Override
	public void setRuptureSet(FaultSystemRupSet rupSet) {
		if (rupSet != this.rupSet) {
			this.rupSet = rupSet;
			
			Preconditions.checkState(rupSet.hasModule(AveSlipModule.class),
					"Rupture set does not have average slip data");
			Preconditions.checkState(rupSet.hasModule(SlipAlongRuptureModel.class),
					"Rupture set does not have slip along rupture data");
			Preconditions.checkState(rupSet.hasModule(SectSlipRates.class),
					"Rupture set does not have slip rate data");
			if (!rupSet.hasModule(ClusterRuptures.class))
				rupSet.addModule(ClusterRuptures.singleStranged(rupSet));
		}
	}

}
