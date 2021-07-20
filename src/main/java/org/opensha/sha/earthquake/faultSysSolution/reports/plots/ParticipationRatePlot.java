package org.opensha.sha.earthquake.faultSysSolution.reports.plots;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.opensha.commons.mapping.gmt.elements.GMT_CPT_Files;
import org.opensha.commons.util.MarkdownUtils;
import org.opensha.commons.util.MarkdownUtils.TableBuilder;
import org.opensha.commons.util.cpt.CPT;
import org.opensha.commons.util.modules.OpenSHA_Module;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.reports.AbstractSolutionPlot;
import org.opensha.sha.earthquake.faultSysSolution.reports.ReportMetadata;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RupSetMapMaker;

public class ParticipationRatePlot extends AbstractSolutionPlot {

	@Override
	public String getName() {
		return "Participation Rates";
	}

	@Override
	public List<String> plot(FaultSystemSolution sol, ReportMetadata meta, File resourcesDir, String relPathToResources,
			String topLink) throws IOException {
		FaultSystemRupSet rupSet = sol.getRupSet();
		double minMag = rupSet.getMinMag();
		double maxMag = rupSet.getMaxMag();
		
		List<Double> minMags = new ArrayList<>();
		List<String> magLabels = new ArrayList<>();
		List<String> magPrefixes = new ArrayList<>();
		
		minMags.add(0d);
		magLabels.add("Supra-Seismogenic");
		magPrefixes.add("supra_seis");
		
		if (maxMag > 6d && minMag <= 6d) {
			minMags.add(6d);
			magLabels.add("M≥6");
			magPrefixes.add("m6");
		}
		
		if (maxMag > 7d && minMag <= 7d) {
			minMags.add(7d);
			magLabels.add("M≥7");
			magPrefixes.add("m7");
		}
		
		if (maxMag > 8d) {
			minMags.add(8d);
			magLabels.add("M≥8");
			magPrefixes.add("m8");
		}
		
		if (maxMag > 9d) {
			minMags.add(9d);
			magLabels.add("M≥9");
			magPrefixes.add("m9");
		}
		
		CPT cpt = GMT_CPT_Files.RAINBOW_UNIFORM.instance().rescale(-5, 0);
		
		RupSetMapMaker mapMaker = new RupSetMapMaker(sol.getRupSet(), meta.region);
		mapMaker.setWriteGeoJSON(true);
		
		TableBuilder table = MarkdownUtils.tableBuilder();
		table.initNewLine();
		table.addColumn("Rate Above");
		table.addColumn("Rate Range");
		CPT ratioCPT = null;
		if (meta.comparison != null && meta.comparison.sol != null) {
			table.addColumn("Comparison");
			CPT belowCPT = new CPT(0.5d, 1d,
					new Color(0, 0, 140), new Color(0, 60, 200 ), new Color(0, 120, 255),
					Color.WHITE);
			CPT aboveCPT = new CPT(1d, 2d,
					Color.WHITE,
					new Color(255, 120, 0), new Color(200, 60, 0), new Color(140, 0, 0));
			ratioCPT = new CPT();
			ratioCPT.addAll(belowCPT);
			ratioCPT.addAll(aboveCPT);
			ratioCPT.setNanColor(Color.GRAY);
			ratioCPT.setBelowMinColor(ratioCPT.getMinColor());
			ratioCPT.setAboveMaxColor(ratioCPT.getMaxColor());
		}
		table.finalizeLine();
		for (int m=0; m<minMags.size(); m++) {
			double myMinMag = minMags.get(m);
			table.initNewLine();
			double[] rates = sol.calcParticRateForAllSects(myMinMag, Double.POSITIVE_INFINITY);
			
			String myLabel = magLabels.get(m);
			String magPrefix = magPrefixes.get(m);
			
			List<String> prefixes = new ArrayList<>();
			
			String mainPrefix = "sol_partic_"+magPrefix;
			mapMaker.plotSectScalars(log10(rates), cpt, "Log10 "+myLabel+" Participation Rate (events/yr)");
			mapMaker.plot(resourcesDir, mainPrefix, " ");
			prefixes.add(mainPrefix);
			
			table.addColumn("![Map]("+relPathToResources+"/"+mainPrefix+".png)");
			
			if (m < minMags.size()-1) {
				double upperMag = minMags.get(m+1);
				double[] range = sol.calcParticRateForAllSects(myMinMag, upperMag);
				String rangePrefix = "sol_partic_"+magPrefix+"_to_"+magPrefixes.get(m+1);
				String label = "Log10 ";
				if (myMinMag == 0d)
					label += myLabel+" -> M"+optionalDigitDF.format(upperMag);
				else
					label += "M"+optionalDigitDF.format(myMinMag)+" -> "+optionalDigitDF.format(upperMag);
				label += " Participation Rate (events/yr)";
				
				mapMaker.plotSectScalars(log10(range), cpt, label);
				mapMaker.plot(resourcesDir, rangePrefix, " ");
				
				table.addColumn("![Map]("+relPathToResources+"/"+rangePrefix+".png)");
				prefixes.add(rangePrefix);
			} else {
				table.addColumn("");
			}
			
			if (meta.comparison != null && meta.comparison.sol != null) {
				double[] compRates = meta.comparison.sol.calcParticRateForAllSects(myMinMag, Double.POSITIVE_INFINITY);
				double[] ratios = new double[compRates.length];
				for (int i=0; i<ratios.length; i++)
					ratios[i] = rates[i]/compRates[i];
				
				String compPrefix = "sol_partic_compare_"+magPrefix;
				mapMaker.plotSectScalars(ratios, ratioCPT, myLabel+" Primary/Comparison Participation Ratio");
				mapMaker.plot(resourcesDir, compPrefix, " ");

				table.addColumn("![Map]("+relPathToResources+"/"+compPrefix+".png)");
				prefixes.add(compPrefix);
			}
			
			table.finalizeLine();
			
			table.initNewLine();
			for (String prefix : prefixes) {
				if (prefix == null)
					table.addColumn("");
				else
					table.addColumn(RupSetMapMaker.getGeoJSONViewerRelativeLink("View GeoJSON", relPathToResources+"/"+prefix+".geojson")
							+" "+"[Download GeoJSON]("+relPathToResources+"/"+prefix+".geojson)");
			}
			table.finalizeLine();
		}
		
		return table.build();
	}
	
	private double[] log10(double[] vals) {
		double[] ret = new double[vals.length];
		for (int i=0; i<ret.length; i++)
			vals[i] = Math.log10(vals[i]);
		return vals;
	}

	@Override
	public Collection<Class<? extends OpenSHA_Module>> getRequiredModules() {
		return null;
	}

}
