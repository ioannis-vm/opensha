package org.opensha.sha.earthquake.faultSysSolution.reports.plots;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.jfree.data.Range;
import org.opensha.commons.data.function.DefaultXY_DataSet;
import org.opensha.commons.data.function.HistogramFunction;
import org.opensha.commons.data.function.XY_DataSet;
import org.opensha.commons.gui.plot.GraphPanel;
import org.opensha.commons.gui.plot.PlotCurveCharacterstics;
import org.opensha.commons.gui.plot.PlotLineType;
import org.opensha.commons.gui.plot.PlotSpec;
import org.opensha.commons.gui.plot.PlotSymbol;
import org.opensha.commons.gui.plot.PlotUtils;
import org.opensha.commons.util.MarkdownUtils;
import org.opensha.commons.util.MarkdownUtils.TableBuilder;
import org.opensha.commons.util.modules.OpenSHA_Module;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.inversion.sa.ConstraintRange;
import org.opensha.sha.earthquake.faultSysSolution.modules.InversionMisfits;
import org.opensha.sha.earthquake.faultSysSolution.reports.AbstractSolutionPlot;
import org.opensha.sha.earthquake.faultSysSolution.reports.ReportMetadata;

import com.google.common.base.Preconditions;

public class InversionMisfitsPlot extends AbstractSolutionPlot {

	@Override
	public String getName() {
		return "Inversion Misfits";
	}

	@Override
	public List<String> plot(FaultSystemSolution sol, ReportMetadata meta, File resourcesDir, String relPathToResources,
			String topLink) throws IOException {
		InversionMisfits misfits = sol.requireModule(InversionMisfits.class);
		
		List<ConstraintRange> ranges = getRanges(misfits);
		
		InversionMisfits compMisfits = null;
		List<ConstraintRange> compRanges = null;
		
		if (meta.comparison != null && meta.comparison.sol != null && meta.comparison.sol.hasModule(InversionMisfits.class)) {
			compMisfits = meta.comparison.sol.requireModule(InversionMisfits.class);
			compRanges = getRanges(compMisfits);
		}
		
		List<String> lines = new ArrayList<>();
		
		for (ConstraintRange range : ranges) {
			String prefix = "misfits_"+range.shortName.replaceAll("\\W+", "_");
			
			lines.add(getSubHeading()+" "+range.name+" Misfits");
			lines.add(topLink); lines.add("");
			
			double[] normMisfits = misfits.getMisfits(range, true);
			MisfitStats stats = new MisfitStats(normMisfits, range.inequality);
			
			ConstraintRange compRange = null;
			double[] compNormMisfits = null;
			MisfitStats compStats = null;
			if (compRanges != null) {
				for (ConstraintRange comp : compRanges) {
					if (comp.name.equals(range.name) && comp.inequality == range.inequality) {
						compRange = comp;
						compNormMisfits = compMisfits.getMisfits(comp, true);
						compStats = new MisfitStats(compNormMisfits, comp.inequality);
						break;
					}
				}
			}
			
			TableBuilder table = MarkdownUtils.tableBuilder();
			
			if (compStats == null)
				table.addLine("Property", "Value");
			else
				table.addLine("Property", "Primary", "Comparison");
			
			table.initNewLine().addColumn("Number of Rows").addColumn(stats.numRows);
			if (compStats != null)
				table.addColumn(compStats.numRows);
			table.finalizeLine();
			
			table.initNewLine().addColumn("Inversion Weight").addColumn(range.weight);
			if (compStats != null)
				table.addColumn(compRange.weight);
			table.finalizeLine();
			
			table.initNewLine().addColumn("Mean Misfit").addColumn((float)stats.mean);
			if (compStats != null)
				table.addColumn((float)compStats.mean);
			table.finalizeLine();
			
			table.initNewLine().addColumn("Abs. Mean Misfit").addColumn((float)stats.absMean);
			if (compStats != null)
				table.addColumn((float)compStats.absMean);
			table.finalizeLine();
			
			table.initNewLine().addColumn("Range").addColumn("["+(float)stats.min+","+(float)stats.max+"]");
			if (compStats != null)
				table.addColumn("["+(float)compStats.min+","+(float)compStats.max+"]");
			table.finalizeLine();
			
			table.initNewLine().addColumn("Standard Deviation").addColumn((float)stats.std);
			if (compStats != null)
				table.addColumn((float)compStats.std);
			table.finalizeLine();
			
			table.initNewLine().addColumn("L2 Norm").addColumn((float)stats.l2Norm);
			if (compStats != null)
				table.addColumn((float)compStats.l2Norm);
			table.finalizeLine();
			
			if (range.inequality) {
				lines.add("_NOTE: This is in inequality constraint, so all misfit values below zero are treated as zeros._");
				lines.add("");
			}
			
			lines.addAll(table.build());
			lines.add("");
			
			// now for plots
			
			HistogramFunction refHist = refHist(stats, compStats);
			File histPlot = buildHistPlot(refHist, normMisfits,
					stats, range, resourcesDir, prefix+"_hist", "Misfit", MAIN_COLOR);
			
			if (compNormMisfits == null) {
				lines.add("![Misfit Plot]("+relPathToResources+"/"+histPlot.getName()+")");
			} else {
				table = MarkdownUtils.tableBuilder();
				table.addLine("Primary", "Comparison");
				table.initNewLine().addColumn("![Misfit Plot]("+relPathToResources+"/"+histPlot.getName()+")");
				File compPlot = buildHistPlot(refHist, compNormMisfits,
						compStats, range, resourcesDir, prefix+"_hist_comp", "Misfit", COMP_COLOR);
				table.addColumn("![Misfit Plot]("+relPathToResources+"/"+compPlot.getName()+")");
				table.finalizeLine();
				
				if (normMisfits.length == compNormMisfits.length) {
					double[] diffs = new double[normMisfits.length];
					XY_DataSet scatter = new DefaultXY_DataSet();
					for (int i=0; i<diffs.length; i++) {
						double n = normMisfits[i];
						double c = compNormMisfits[i];
						if (range.inequality) {
							n = Math.max(n, 0d);
							c = Math.max(c, 0d);
						}
						diffs[i] = n - c;
						scatter.set(n, c);
					}
					
					refHist = refHist(new MisfitStats(diffs, false), null);
					File histDiffPlot = buildHistPlot(refHist, normMisfits,
							null, range, resourcesDir, prefix+"_diff", "Difference: Primary - Comparison", COMMON_COLOR);
					table.initNewLine().addColumn("![Diff Plot]("+relPathToResources+"/"+histDiffPlot.getName()+")");
					List<XY_DataSet> funcs = new ArrayList<>();
					List<PlotCurveCharacterstics> chars = new ArrayList<>();
					Range scatterRange = new Range(Math.min(scatter.getMinX(), scatter.getMinY()),
							Math.max(scatter.getMaxX(), scatter.getMaxY()));
					DefaultXY_DataSet oneToOne = new DefaultXY_DataSet();
					oneToOne.set(scatterRange.getLowerBound(), scatterRange.getLowerBound());
					oneToOne.set(scatterRange.getUpperBound(), scatterRange.getUpperBound());
					funcs.add(oneToOne);
					chars.add(new PlotCurveCharacterstics(PlotLineType.DASHED, 2f, Color.GRAY));
					funcs.add(scatter);
					chars.add(new PlotCurveCharacterstics(PlotSymbol.CROSS, 3f, Color.BLACK));
					PlotSpec scatterSpec = new PlotSpec(funcs, chars, "Primary vs Comparison",
							getTruncatedTitle(meta.primary.name), getTruncatedTitle(meta.comparison.name));

					GraphPanel gp = PlotUtils.initHeadless();
					
					gp.drawGraphPanel(scatterSpec, false, false, scatterRange, scatterRange);
					
					String scatterPrefix = prefix+"_scatter";
					PlotUtils.writePlots(resourcesDir, scatterPrefix, gp, 800, 650, true, true, false);
					table.addColumn("![Scatter Plot]("+relPathToResources+"/"+scatterPrefix+".png)");
					
					table.finalizeLine();
				}
				lines.addAll(table.build());
			}
			lines.add("");
		}
		
		return lines;
	}
	
	private static HistogramFunction refHist(MisfitStats stats, MisfitStats compStats) {
		double min = Math.min(0, stats.min);
		double max = stats.max;
		if (compStats != null) {
			min = Math.min(min, compStats.min);
			max = Math.max(max, compStats.max);
		}
		if (max <= min)
			max = 1d;
		double span = max - min;
		Preconditions.checkState(span > 0d);
		double histDelta = Math.max(1e-6, Math.pow(10, Math.floor(Math.log10(span))-1)/2);
		return HistogramFunction.getEncompassingHistogram(min, max, histDelta);
	}
	
	private static List<ConstraintRange> getRanges(InversionMisfits misfits) {
		List<ConstraintRange> ranges = misfits.getConstraintRanges();
		if (ranges == null || ranges.isEmpty()) {
			double[] eqMisfits = misfits.getMisfits();
			double[] ineqMisfits = misfits.getInequalityMisfits();
			
			ranges = new ArrayList<>();
			if (eqMisfits != null)
				ranges.add(new ConstraintRange("Equality Constraints", "Equality", 0, eqMisfits.length, false, 1d));
			if (ineqMisfits != null)
				ranges.add(new ConstraintRange("Inequality Constraints", "Inequality", 0, ineqMisfits.length, true, 1d));
		}
		return ranges;
	}
	
	private static class MisfitStats {
		private int numRows;
		private double mean;
		private double absMean;
		private double min;
		private double max;
		private double l2Norm;
		private double std;
		
		public MisfitStats(double[] misfits, boolean inequality) {
			numRows = misfits.length;
			StandardDeviation std = new StandardDeviation();
			for (double val : misfits) {
				if (inequality && val < 0d)
					val = 0d;
				mean += val;
				absMean += Math.abs(val);
				min = Math.min(val, min);
				max = Math.max(val, max);
				l2Norm += val*val;
				std.increment(val);
			}
			mean /= (double)numRows;
			absMean /= (double)numRows;
			this.std = std.getResult();
		}
	}
	
	private static File buildHistPlot(HistogramFunction refHist, double[] data, MisfitStats stats,
			ConstraintRange range, File outputDir, String prefix, String xAxisLabel, Color color) throws IOException {
		HistogramFunction hist = new HistogramFunction(refHist.getMinX(), refHist.getMaxX(), refHist.size());
		for (double val : data) {
			if (range.inequality && val < 0)
				continue;
			hist.add(hist.getClosestXIndex(val), 1d);
		}
		
		hist.setName("Histogram");
		
		List<XY_DataSet> funcs = new ArrayList<>();
		List<PlotCurveCharacterstics> chars = new ArrayList<>();
		
		funcs.add(hist);
		chars.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM,1f, color));
		
		double minX = hist.getMinX()-0.5*hist.getDelta();
		double maxX = hist.getMaxX()+0.5*hist.getDelta();
		
		if (stats != null)
			maxX = Math.max(maxX, 1.05*stats.absMean);
		
		Range xRange = new Range(minX, maxX);
		Range yRange = new Range(0d, Math.max(1d, hist.getMaxY()*1.05));
		
		if (stats != null) {
			funcs.add(vertLine(stats.mean, 0d, yRange.getUpperBound(), "Mean"));
			chars.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.CYAN.darker()));
			
			funcs.add(vertLine(stats.absMean, 0d, yRange.getUpperBound(), "|Mean|"));
			chars.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.MAGENTA.darker()));
		}
		
		PlotSpec spec = new PlotSpec(funcs, chars, range.name+" Misfits", xAxisLabel, "Count");
		spec.setLegendVisible(true);
		
		GraphPanel gp = PlotUtils.initHeadless();
		
		gp.drawGraphPanel(spec, false, false, xRange, yRange);
		
		PlotUtils.writePlots(outputDir, prefix, gp, 800, 650, true, true, false);
		
		return new File(outputDir, prefix+".png");
	}
	
	private static XY_DataSet vertLine(double x, double y0, double y1, String name) {
		DefaultXY_DataSet xy = new DefaultXY_DataSet();
		xy.setName(name);
		xy.set(x, y0);
		xy.set(x, y1);
		return xy;
	}

	@Override
	public Collection<Class<? extends OpenSHA_Module>> getRequiredModules() {
		return Collections.singleton(InversionMisfits.class);
	}

}
