package org.opensha.sha.earthquake.faultSysSolution.reports.plots;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.Range;
import org.opensha.commons.data.function.DefaultXY_DataSet;
import org.opensha.commons.data.function.EvenlyDiscretizedFunc;
import org.opensha.commons.data.function.HistogramFunction;
import org.opensha.commons.data.function.XY_DataSet;
import org.opensha.commons.data.xyz.EvenlyDiscrXYZ_DataSet;
import org.opensha.commons.gui.plot.HeadlessGraphPanel;
import org.opensha.commons.gui.plot.PlotCurveCharacterstics;
import org.opensha.commons.gui.plot.PlotLineType;
import org.opensha.commons.gui.plot.PlotSpec;
import org.opensha.commons.gui.plot.PlotSymbol;
import org.opensha.commons.gui.plot.PlotUtils;
import org.opensha.commons.gui.plot.jfreechart.xyzPlot.XYZGraphPanel;
import org.opensha.commons.gui.plot.jfreechart.xyzPlot.XYZPlotSpec;
import org.opensha.commons.mapping.gmt.elements.GMT_CPT_Files;
import org.opensha.commons.util.Interpolate;
import org.opensha.commons.util.MarkdownUtils;
import org.opensha.commons.util.MarkdownUtils.TableBuilder;
import org.opensha.commons.util.cpt.CPT;
import org.opensha.commons.util.modules.OpenSHA_Module;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.InitialSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.WaterLevelRates;
import org.opensha.sha.earthquake.faultSysSolution.reports.AbstractSolutionPlot;
import org.opensha.sha.earthquake.faultSysSolution.reports.ReportMetadata;

import scratch.UCERF3.simulatedAnnealing.SimulatedAnnealing;
import scratch.UCERF3.simulatedAnnealing.ThreadedSimulatedAnnealing;
import scratch.UCERF3.simulatedAnnealing.completion.AnnealingProgress;

public class InversionProgressPlot extends AbstractSolutionPlot {

	@Override
	public String getName() {
		return "Simulated Annealing Energy";
	}

	@Override
	public List<String> plot(FaultSystemSolution sol, ReportMetadata meta, File resourcesDir, String relPathToResources,
			String topLink) throws IOException {
		AnnealingProgress progress = sol.requireModule(AnnealingProgress.class);
		
		List<String> lines = new ArrayList<>();
		
		long millis = progress.getTime(progress.size()-1);
		double secs = millis/1000d;
		double mins = secs/60d;
		double hours = mins/60d;
		long perturbs = progress.getNumPerturbations(progress.size()-1);
		long iters = progress.getIterations(progress.size()-1);
		double totalEnergy = progress.getEnergies(progress.size()-1)[0];

		int ips = (int)((double)iters/secs + 0.5);
		
		lines.add("* Iterations: "+countDF.format(iters)+" ("+countDF.format(ips)+" /sec)");
		lines.add("* Time: "+ThreadedSimulatedAnnealing.timeStr(millis));
		lines.add("* Perturbations: "+countDF.format(perturbs));
		lines.add("* Total energy: "+(float)totalEnergy);
		
		lines.add(getSubHeading()+" Final Energies");
		lines.add(topLink); lines.add("");
		
		TableBuilder table = MarkdownUtils.tableBuilder();
		long deltaEachMillis;
		if (hours > 20)
			deltaEachMillis = 1000l*60l*60l*5l; // 5 hours
		else if (hours > 9)
			deltaEachMillis = 1000l*60l*60l*2l; // 2 hours
		else if (hours > 3)
			deltaEachMillis = 1000l*60l*60l*1l; // 1 hour
		else if (hours > 1)
			deltaEachMillis = 1000l*60l*30l; // 30 mins
		else if (mins > 30)
			deltaEachMillis = 1000l*60l*15l; // 15 mins
		else if (mins > 10)
			deltaEachMillis = 1000l*60l*5l; // 5 mins
		else
			deltaEachMillis = 1000l*60l*5l; // 1 min
		table.initNewLine().addColumn("Energy Type").addColumn("Final Energy ("
			+ThreadedSimulatedAnnealing.timeStr(progress.getTime(progress.size()-1))+")").addColumn("% of Total");
		List<Long> progressTimes = new ArrayList<>();
		List<Integer> progressIndexAfters = new ArrayList<>();
		int curIndex = 0;
		long maxTimeToInclude = (long)(millis*0.95d);
		for (long t=deltaEachMillis; t<maxTimeToInclude; t+=deltaEachMillis) {
			if (t < progress.getTime(0))
				continue;
			progressTimes.add(t);
			String str = "";
			if (t == deltaEachMillis)
				str = "After ";
			str += ThreadedSimulatedAnnealing.timeStr(t);
//			System.out.println(str+" at "+t);
			table.addColumn("_"+str+"_");
			while (curIndex < progress.size()) {
				long time = progress.getTime(curIndex);
				if (time >= t)
					break;
				curIndex++;
			}
			progressIndexAfters.add(curIndex);
		}
		table.finalizeLine();
		
		double[] finalEnergies = progress.getEnergies(progress.size()-1);
		List<String> types = progress.getEnergyTypes();
		for (int t=0; t<types.size(); t++) {
			table.initNewLine();
			table.addColumn("**"+types.get(t)+"**");
			if (t == 0)
				table.addColumn("**"+(float)finalEnergies[t]+"**").addColumn("");
			else
				table.addColumn((float)finalEnergies[t]).addColumn(percentDF.format(finalEnergies[t]/finalEnergies[0]));
			for (int i=0; i<progressTimes.size(); i++) {
				long time = progressTimes.get(i);
				int i1 = progressIndexAfters.get(i);
				double val;
				if (i1 == 0) {
					val = progress.getEnergies(i1)[t];
				} else if (i1 >= progress.size()) {
					val = progress.getEnergies(progress.size()-1)[t];
				} else {
					// interpolate
					int i0 = i1-1;
					double x1 = progress.getTime(i0);
					double x2 = progress.getTime(i1);
					double y1 = progress.getEnergies(i0)[t];
					double y2 = progress.getEnergies(i1)[t];
					val = Interpolate.findY(x1, y1, x2, y2, time);
				}
				String str = (float)val+"";
				if (i1 == 0 || i1 >= progress.size())
					str += "*";
				table.addColumn("_"+str+"_");
			}
			table.finalizeLine();
		}
		lines.addAll(table.build());
		
		// now plots
		String prefix = "sa_progress";
		SimulatedAnnealing.writeProgressPlots(progress, resourcesDir, prefix, sol.getRupSet().getNumRuptures());
		
		lines.add("");
		lines.add(getSubHeading()+" Energy Progress");
		lines.add(topLink); lines.add("");
		
		lines.add("![Energy vs Time]("+relPathToResources+"/"+prefix+"_energy_vs_time.png)");
		lines.add("");
		
		lines.add("![Energy vs Iterations]("+relPathToResources+"/"+prefix+"_energy_vs_iters.png)");
		lines.add("");
		
		lines.add("![Perturbations]("+relPathToResources+"/"+prefix+"_perturb_vs_iters.png)");
		
		lines.add("");
		lines.add(getSubHeading()+" Rate Distribution");
		lines.add(topLink); lines.add("");
		
		double[] rates = sol.getRateForAllRups();
		double[] ratesNoMin;
		if (sol.hasModule(WaterLevelRates.class))
			ratesNoMin = sol.getModule(WaterLevelRates.class).subtractFrom(rates);
		else
			ratesNoMin = rates;
		double[] initial = sol.hasModule(InitialSolution.class) ?
				sol.getModule(InitialSolution.class).get() : new double[rates.length];
		
		int numNonZero = 0;
		int numAboveWaterlevel = 0;
		for (int r=0; r<rates.length; r++) {
			if (rates[r] > 0) {
				numNonZero++;
				if (ratesNoMin[r] > 0)
					numAboveWaterlevel++;
			}
		}
		lines.add("* Non-zero ruptures: "+countDF.format(numNonZero)
			+" ("+percentDF.format((double)numNonZero/(double)rates.length)+")");
		if (ratesNoMin != rates)
			lines.add("* Ruptures above water-level: "+countDF.format(numAboveWaterlevel)
				+" ("+percentDF.format((double)numAboveWaterlevel/(double)rates.length)+")");
		lines.add("* Avg. # perturbations per rupture: "+(float)(perturbs/(double)rates.length));
		lines.add("* Avg. # perturbations per perturbed rupture: "+(float)(perturbs/(double)numAboveWaterlevel));
		
		SimulatedAnnealing.writeRateVsRankPlot(resourcesDir, prefix+"_rate_dist", ratesNoMin, rates, initial);
		lines.add("![Rate Distribution]("+relPathToResources+"/"+prefix+"_rate_dist.png)");
		lines.add("");
		lines.add("![Cumulative Rate Distribution]("+relPathToResources+"/"+prefix+"_rate_dist_cumulative.png)");
		
		DefaultXY_DataSet magRateScatter = new DefaultXY_DataSet();
		double[] mags = sol.getRupSet().getMagForAllRups();
		for (int r=0; r<mags.length; r++)
			magRateScatter.set(mags[r], rates[r]);
		
		List<XY_DataSet> funcs = new ArrayList<>();
		List<PlotCurveCharacterstics> chars = new ArrayList<>();
		
		funcs.add(magRateScatter);
		chars.add(new PlotCurveCharacterstics(PlotSymbol.CROSS, 3f, Color.BLACK));
		
		Range xRange = new Range(0.5*Math.floor(magRateScatter.getMinX()*2), 0.5*Math.ceil(magRateScatter.getMaxX()*2));
		Range yRange = new Range(Math.max(1e-16, magRateScatter.getMinY()), Math.max(1e-2, magRateScatter.getMaxY()));
		Range logYRange = new Range(Math.log10(yRange.getLowerBound()), Math.log10(yRange.getUpperBound()));
		
		PlotSpec spec = new PlotSpec(funcs, chars, "Rate vs Magnitude", "Magnitude", "Rupture Rate (/yr)");
		
		HeadlessGraphPanel gp = PlotUtils.initHeadless();
		
		gp.drawGraphPanel(spec, false, true, xRange, yRange);
		
		PlotUtils.writePlots(resourcesDir, prefix+"_rate_vs_mag", gp, 1000, 850, true, false, false);
//		lines.add("");
//		lines.add("![Rate vs Mag]("+relPathToResources+"/"+prefix+"_rate_vs_mag.png)");
		
		funcs = new ArrayList<>();
		chars = new ArrayList<>();
		EvenlyDiscretizedFunc refXFunc = new EvenlyDiscretizedFunc(xRange.getLowerBound(), xRange.getUpperBound(), 50);
		EvenlyDiscretizedFunc refYFunc = new EvenlyDiscretizedFunc(logYRange.getLowerBound(), logYRange.getUpperBound(), 50);
		EvenlyDiscrXYZ_DataSet xyz = new EvenlyDiscrXYZ_DataSet(refXFunc.size(), refYFunc.size(),
				refXFunc.getMinX(), refYFunc.getMinX(), refXFunc.getDelta(), refYFunc.getDelta());
		for (Point2D pt : magRateScatter) {
			double x = pt.getX();
			double logY = Math.log10(pt.getY());
			int xInd = refXFunc.getClosestXIndex(x);
			int yInd = refYFunc.getClosestXIndex(logY);
			xyz.set(xInd, yInd, xyz.get(xInd, yInd)+1);
		}
		
		xyz.log10();
		double maxZ = xyz.getMaxZ();
		CPT cpt = GMT_CPT_Files.BLACK_RED_YELLOW_UNIFORM.instance().reverse().rescale(0d, Math.ceil(maxZ));
		cpt.setNanColor(new Color(255, 255, 255, 0));
		cpt.setBelowMinColor(cpt.getNaNColor());
		
		// set all zero to NaN so that it will plot clear
		for (int i=0; i<xyz.size(); i++) {
			if (xyz.get(i) == 0)
				xyz.set(i, Double.NaN);
		}
		
		XYZPlotSpec xyzSpec = new XYZPlotSpec(xyz, cpt, spec.getTitle(), spec.getXAxisLabel(),
				"Log10 "+spec.getYAxisLabel(), "Log10 Count");
		xyzSpec.setCPTPosition(RectangleEdge.BOTTOM);
		xyzSpec.setXYElems(funcs);
		xyzSpec.setXYChars(chars);
		XYZGraphPanel xyzGP = new XYZGraphPanel(gp.getPlotPrefs());
		xyzGP.drawPlot(xyzSpec, false, false, xRange, logYRange);
		
		xyzGP.getChartPanel().setSize(1000, 850);
		xyzGP.saveAsPNG(new File(resourcesDir, prefix+"_rate_vs_mag_hist2D.png").getAbsolutePath());
		
		table = MarkdownUtils.tableBuilder();
		table.initNewLine();
		table.addColumn("![rate comparison]("+relPathToResources+"/"+prefix+"_rate_vs_mag.png)");
		table.addColumn("![rate hist]("+relPathToResources+"/"+prefix+"_rate_vs_mag_hist2D.png)");
		table.finalizeLine();
		lines.add("");
		lines.addAll(table.build());
		
		
		return lines;
	}

	@Override
	public Collection<Class<? extends OpenSHA_Module>> getRequiredModules() {
		return List.of(AnnealingProgress.class);
	}

}
