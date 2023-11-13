package org.opensha.sha.earthquake.rupForecastImpl.nshm23.targetMFDs.estimators;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.stat.StatUtils;
import org.jfree.data.Range;
import org.opensha.commons.data.function.DefaultXY_DataSet;
import org.opensha.commons.data.function.XY_DataSet;
import org.opensha.commons.data.uncertainty.UncertainIncrMagFreqDist;
import org.opensha.commons.gui.plot.HeadlessGraphPanel;
import org.opensha.commons.gui.plot.PlotCurveCharacterstics;
import org.opensha.commons.gui.plot.PlotSpec;
import org.opensha.commons.gui.plot.PlotSymbol;
import org.opensha.commons.gui.plot.PlotUtils;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.inversion.Inversions;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.JumpProbabilityConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.JumpProbabilityConstraint.SectParticipationRateEstimator;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.JumpProbabilityCalc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.JumpProbabilityCalc.BinaryJumpProbabilityCalc;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.targetMFDs.SupraSeisBValInversionTargetMFDs;

import com.google.common.base.Preconditions;

/**
 * A priori estimation of section participation rates and rupture rates consistent with
 * {@link SupraSeisBValInversionTargetMFDs} implementation. Can be used to weight rate-based
 * {@link JumpProbabilityConstraint} implementations, and as a variable perturbation basis in the inversion.
 * 
 * @author kevin
 *
 */
public class GRParticRateEstimator implements SectParticipationRateEstimator {
	
	private double[] estParticRates;
	private double[] estRupRates;

	public GRParticRateEstimator(FaultSystemRupSet rupSet, double supraSeisB) {
		this(rupSet, supraSeisB, null);
	}

	public GRParticRateEstimator(FaultSystemRupSet rupSet, double supraSeisB, JumpProbabilityCalc segModel) {
		SupraSeisBValInversionTargetMFDs.Builder builder = new SupraSeisBValInversionTargetMFDs.Builder(rupSet, supraSeisB);
		if (segModel != null) {
			if (segModel instanceof BinaryJumpProbabilityCalc)
				builder.forBinaryRupProbModel((BinaryJumpProbabilityCalc)segModel);
			else
				builder.adjustTargetsForData(new ThresholdAveragingSectNuclMFD_Estimator.RelGRWorstJumpProb(segModel, 100, true));
//				builder.adjustTargetsForData(new SegmentationImpliedSectNuclMFD_Estimator(segModel));
		}
		builder.applyDefModelUncertainties(false);
		init(rupSet, builder.build());
	}

	public GRParticRateEstimator(FaultSystemRupSet rupSet, SupraSeisBValInversionTargetMFDs targetMFDs) {
		init(rupSet, targetMFDs);
	}
	
	private void init(FaultSystemRupSet rupSet, SupraSeisBValInversionTargetMFDs targetMFDs) {
		List<UncertainIncrMagFreqDist> sectSupraMFDs = targetMFDs.getOnFaultSupraSeisNucleationMFDs();
		
		estParticRates = new double[rupSet.getNumSections()];
		estRupRates = new double[rupSet.getNumRuptures()];
		
		for (int s=0; s<estParticRates.length; s++) {
			UncertainIncrMagFreqDist nuclGR = sectSupraMFDs.get(s);
			
			List<Integer> rups = targetMFDs.getRupturesForSect(s);
			List<Double> rupMags = new ArrayList<>();
			int[] rupsPerBin = new int[nuclGR.size()];
			for (int r : rups) {
				double rupMag = rupSet.getMagForRup(r);
				rupMags.add(rupMag);
				rupsPerBin[nuclGR.getClosestXIndex(rupMag)]++;
			}
			
			if (rups.isEmpty()) {
				Preconditions.checkState(nuclGR.calcSumOfY_Vals() == 0d);
				continue;
			}
			
			double sectArea = rupSet.getAreaForSection(s);
			
			// spread to all ruptures evenly to get partic rate
			double calcRate = 0d;
			for (int r=0; r<rups.size(); r++) {
				int bin = nuclGR.getClosestXIndex(rupMags.get(r));
				/// this is a nucleation rate
				double nuclRate = nuclGR.getY(bin)/(double)rupsPerBin[bin];
				// turn back into participation rate
				double particRate = nuclRate*rupSet.getAreaForRup(rups.get(r))/sectArea;
				// adjust for visibility
				calcRate += particRate;
				
				// estimated rup rates should sum nucleation rates
				estRupRates[rups.get(r)] += nuclRate;
			}
			estParticRates[s] = calcRate;
		}
	}

	@Override
	public double[] estimateSectParticRates() {
		return estParticRates;
	}

	@Override
	public double estimateSectParticRate(int sectionIndex) {
		return estParticRates[sectionIndex];
	}
	
	public double[] estimateRuptureRates() {
		return estRupRates;
	}
	
	public static void main(String[] args) throws IOException {
//		double supraB = 0.8;
//		FaultSystemSolution sol = FaultSystemSolution.load(new File("/home/kevin/OpenSHA/UCERF4/batch_inversions/"
//				+ "2021_12_08-coulomb-fm31-ref_branch-uniform-nshm23_draft_default-supra_b_0.8-2h/run_0/solution.zip"));
		double supraB = 0.0;
		FaultSystemSolution sol = FaultSystemSolution.load(new File("/home/kevin/OpenSHA/UCERF4/batch_inversions/"
				+ "2021_11_19-reproduce-ucerf3-ref_branch-uniform-nshm23_draft-supra_b_sweep-u3_supra_reduction"
				+ "-no_paleo-no_parkfield-mfd_wt_10-sect_wt_0.5-skipBelow-2h/"
				+ "2021_11_19-reproduce-ucerf3-ref_branch-uniform-nshm23_draft-supra_b_"+(float)supraB
				+ "-u3_supra_reduction-no_paleo-no_parkfield-mfd_wt_10-sect_wt_0.5-skipBelow-2h/mean_solution.zip"));
		
		File outputDir = new File("/tmp");
		
		double[] solPartics = sol.calcParticRateForAllSects(0d, Double.POSITIVE_INFINITY);
		
		List<SectParticipationRateEstimator> estimators = new ArrayList<>();
		List<String> estimatorNames = new ArrayList<>();
		List<String> estimatorPrefixes = new ArrayList<>();
		
		GRParticRateEstimator grEst = new GRParticRateEstimator(sol.getRupSet(), supraB);
		
		estimators.add(grEst);
		estimatorNames.add("G-R estimate");
		estimatorPrefixes.add("gr");
		
		estimators.add(new JumpProbabilityConstraint.InitialModelParticipationRateEstimator(sol.getRupSet(),
				grEst.estimateRuptureRates()));
		estimatorNames.add("G-R rate est");
		estimatorPrefixes.add("gr_rate");
		
		double[] prevRateEst = Inversions.getDefaultVariablePerturbationBasis(sol.getRupSet());
		estimators.add(new JumpProbabilityConstraint.InitialModelParticipationRateEstimator(sol.getRupSet(),
				prevRateEst));
		estimatorNames.add("Smooth starting model estimate");
		estimatorPrefixes.add("smooth_start");
		
		System.out.println("Prev rate est sum: "+StatUtils.sum(prevRateEst));
		System.out.println("New rate est sum: "+StatUtils.sum(grEst.estRupRates));
		
		for (int i=0; i<estimators.size(); i++) {
			DefaultXY_DataSet xy = new DefaultXY_DataSet();
			SectParticipationRateEstimator estimator = estimators.get(i);
			
			for (int s=0; s<solPartics.length; s++)
				xy.set(solPartics[s], estimator.estimateSectParticRate(s));
			
			List<XY_DataSet> funcs = List.of(xy);
			List<PlotCurveCharacterstics> chars = List.of(new PlotCurveCharacterstics(PlotSymbol.CROSS, 3f, Color.BLACK));
			
			PlotSpec plot = new PlotSpec(funcs, chars, "Participation Rate Comparison", "Acutal Solution", estimatorNames.get(i));
			
			Range range = new Range(Math.min(xy.getMinX(), xy.getMinY()), Math.max(xy.getMaxX(), xy.getMaxY()));
			range = new Range(Math.pow(10, Math.floor(Math.log10(range.getLowerBound()))),
					Math.pow(10, Math.ceil(Math.log(range.getUpperBound()))));
			
			HeadlessGraphPanel gp = PlotUtils.initHeadless();
			
			gp.drawGraphPanel(plot, true, true, range, range);
			
			String prefix = "partic_rate_vs_"+estimatorPrefixes.get(i);
			
			PlotUtils.writePlots(outputDir, prefix, gp, 800, false, true, false, false);
		}
	}

}
