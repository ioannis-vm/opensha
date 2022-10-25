package scratch.UCERF3.utils;

import java.io.File;
import java.io.IOException;

import org.opensha.commons.util.ClassUtils;

import scratch.UCERF3.U3CompoundFaultSystemSolution;
import scratch.UCERF3.U3FaultSystemSolution;
import scratch.UCERF3.logicTree.U3LogicTreeBranch;

import com.google.common.base.Preconditions;

public class CompoundSolBranchExtractor {

	public static void main(String[] args) {
		if (args.length != 3) {
			String cl = ClassUtils.getClassNameWithoutPackage(CompoundSolBranchExtractor.class);
			System.err.println("USAGE: "+cl+" <compound-sol-file> <branch> <output>");
			System.err.println("");
			System.err.println("<output> can either be a directory, or a file name");
			System.err.println("EXAMPLE: "+cl+" compound_sol.zip "
					+ "FM3_1_ZENGBB_Shaw09Mod_DsrTap_CharConst_M5Rate7.9_MMaxOff7.6_NoFix_SpatSeisU3 /tmp/");
			System.exit(2);
		}
		
		// load compound solution
		File compoundFile = new File(args[0]);
		Preconditions.checkState(compoundFile.exists(),
				"Compound fault system solution file does not exist: "+compoundFile.getAbsolutePath());
		U3CompoundFaultSystemSolution cfss;
		try {
			cfss = U3CompoundFaultSystemSolution.fromZipFile(compoundFile);
		} catch (Exception e) {
			throw new IllegalStateException("Could not parse compound fault system solution file, ensure correct file type", e);
		}
		
		// parse branch
		U3LogicTreeBranch branch;
		try {
			branch = U3LogicTreeBranch.fromFileName(args[1]);
		} catch (Exception e) {
			throw new IllegalStateException("Could not parse Logic Tree Branch from: "+args[1], e);
		}
		Preconditions.checkState(branch.isFullySpecified(), "Branch is not fully specified:\n\tInput: "+args[1]+"\n\tParsed: "+branch);
		
		U3FaultSystemSolution sol;
		try {
			sol = cfss.getSolution(branch);
		} catch (Exception e) {
			throw new IllegalStateException("Error loading solution. Possible that it doesn't exist in the given file.", e);
		}
		
		File outputFile = new File(args[2]);
		if (outputFile.isDirectory())
			outputFile = new File(outputFile, branch.buildFileName()+".zip");
		
		System.out.println("Writing branch solution to: "+outputFile.getAbsolutePath());
		try {
			U3FaultSystemIO.writeSol(sol, outputFile);
		} catch (IOException e) {
			throw new IllegalStateException("Error writing solution file.", e);
		}
	}

}
