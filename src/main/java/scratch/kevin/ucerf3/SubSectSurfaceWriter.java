package scratch.kevin.ucerf3;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.map.HashedMap;
import org.dom4j.DocumentException;
import org.opensha.commons.data.CSVFile;
import org.opensha.commons.geo.Location;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.faultSurface.EvenlyGriddedSurface;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.faultSurface.RuptureSurface;
import org.opensha.sha.faultSurface.StirlingGriddedSurface;

import com.google.common.base.Preconditions;

import scratch.UCERF3.U3FaultSystemSolution;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.utils.U3FaultSystemIO;

class SubSectSurfaceWriter {
	
	public static void main(String[] args) throws IOException, DocumentException {
		Map<FaultModels, U3FaultSystemSolution> sols = new HashedMap<>();
		
		File solDir = new File("/home/kevin/workspace/opensha-ucerf3/src/scratch/UCERF3/data/scratch/InversionSolutions");
		sols.put(FaultModels.FM3_1, U3FaultSystemIO.loadSol(
				new File(solDir, "2013_05_10-ucerf3p3-production-10runs_COMPOUND_SOL_FM3_1_MEAN_BRANCH_AVG_SOL.zip")));
		sols.put(FaultModels.FM3_2, U3FaultSystemIO.loadSol(
				new File(solDir, "2013_05_10-ucerf3p3-production-10runs_COMPOUND_SOL_FM3_2_MEAN_BRANCH_AVG_SOL.zip")));
		
		double gridSpacing = 1d;
		File mainDir = new File("/home/kevin/OpenSHA/UCERF3/sub_sect_surfaces");
		
		List<String> header = new ArrayList<String>();
		header.add("Row");
		header.add("Column");
		header.add("Latitude");
		header.add("Longitude");
		header.add("Depth");
		
		for (FaultModels fm : sols.keySet()) {
			System.out.println(fm);
			FaultSystemRupSet rupSet = sols.get(fm).getRupSet();
			for (boolean reduce : new boolean[] {false, true}) {
				String dirName = fm.encodeChoiceString()+"_"+(float)gridSpacing+"km";
				if (reduce)
					dirName += "_reduced";
				else
					dirName += "_full";
				File outputDir = new File(mainDir, dirName);
				System.out.println("Writing to: "+outputDir.getAbsolutePath());
				Preconditions.checkState(outputDir.exists() || outputDir.mkdir());
				for (int s=0; s<rupSet.getNumSections(); s++) {
					FaultSection fsd = rupSet.getFaultSectionData(s);
					RuptureSurface surf = fsd.getFaultSurface(gridSpacing, false, reduce);
					Preconditions.checkState(surf instanceof EvenlyGriddedSurface);
					EvenlyGriddedSurface gridSurf = (EvenlyGriddedSurface)surf;
					FileWriter fw = new FileWriter(new File(outputDir, s+".txt"));
					CSVFile<String> csv = new CSVFile<>(true);
					csv.addLine(header);
					fw.write("# "+fsd.getName()+"\n");
					fw.write("# rows: "+gridSurf.getNumRows()+"\n");
					fw.write("# columns: "+gridSurf.getNumCols()+"\n");
					fw.write("# points: "+gridSurf.size()+"\n");
					fw.write("# <row> <column> <latitude> <longitude> <depth>\n");
					for (int row=0; row<gridSurf.getNumRows(); row++) {
						for (int col=0; col<gridSurf.getNumCols(); col++) {
							Location loc = gridSurf.get(row, col);
							fw.write(row+" "+col+" "+loc.getLatitude()+" "+loc.getLongitude()+" "+loc.getDepth()+"\n");
							List<String> line = new ArrayList<>();
							line.add(row+"");
							line.add(col+"");
							line.add(loc.getLatitude()+"");
							line.add(loc.getLongitude()+"");
							line.add(loc.getDepth()+"");
							csv.addLine(line);
						}
					}
					fw.close();
					csv.writeToFile(new File(outputDir, s+".csv"));
				}
			}
		}
	}

}
