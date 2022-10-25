package scratch.UCERF3;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.math3.stat.StatUtils;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.FileNameComparator;

import scratch.UCERF3.inversion.BatchPlotGen;
import scratch.UCERF3.inversion.InversionFaultSystemSolution;
import scratch.UCERF3.logicTree.U3LogicTreeBranch;
import scratch.UCERF3.logicTree.VariableLogicTreeBranch;
import scratch.UCERF3.utils.U3FaultSystemIO;
import scratch.UCERF3.utils.MatrixIO;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class U3FileBasedFSSIterator extends U3FaultSystemSolutionFetcher {
	
	public static final String TAG_BUILD_MEAN = "BUILD_MEAN";
	
	private Map<U3LogicTreeBranch, File[]> filesMap;
	
	public U3FileBasedFSSIterator(Map<U3LogicTreeBranch, File[]> filesMap) {
		this.filesMap = filesMap;
	}
	
	public static U3FileBasedFSSIterator forDirectory(File dir) {
		return forDirectory(dir, Integer.MAX_VALUE);
	}
	
	public static U3FileBasedFSSIterator forDirectory(File dir, int maxDepth) {
		return forDirectory(dir, Integer.MAX_VALUE, null);
	}
	
	public static U3FileBasedFSSIterator forDirectory(File dir, int maxDepth, List<String> nameGreps) {
		return new U3FileBasedFSSIterator(solFilesForDirectory(dir, maxDepth, nameGreps));
	}
	
	private static Map<U3LogicTreeBranch, File[]> solFilesForDirectory(
			File dir, int maxDepth, List<String> nameGreps) {
		Map<U3LogicTreeBranch, File[]> files = Maps.newHashMap();
		
		boolean assembleMean = nameGreps != null && nameGreps.contains(TAG_BUILD_MEAN);
		
		List<String> myNameGreps;
		if (assembleMean) {
			myNameGreps = Lists.newArrayList(nameGreps);
			myNameGreps.remove(TAG_BUILD_MEAN);
		} else {
			myNameGreps = nameGreps;
		}
		
		fileLoop:
		for (File file : dir.listFiles()) {
			if (file.isDirectory() && maxDepth > 0) {
				Map<U3LogicTreeBranch, File[]> subFiles = solFilesForDirectory(file, maxDepth-1, nameGreps);
				for (U3LogicTreeBranch branch : subFiles.keySet()) {
					if (assembleMean) {
						File[] newFiles = subFiles.get(branch);
						if (files.containsKey(branch)) {
							File[] origFiles = files.get(branch);
							File[] combined = new File[newFiles.length+origFiles.length];
							System.arraycopy(origFiles, 0, combined, 0, origFiles.length);
							System.arraycopy(newFiles, 0, combined, origFiles.length, newFiles.length);
							files.put(branch, combined);
						} else {
							files.put(branch, newFiles);
						}
					} else {
						checkNoDuplicates(branch, subFiles.get(branch)[0], files);
						files.put(branch, subFiles.get(branch));
					}
				}
				continue;
			}
			String name = file.getName();
			boolean solFile = name.endsWith("_sol.zip");
			if (!solFile && name.endsWith(".bin") && !name.contains("noMinRates")) {
				// if the sol.zip is available, use that instead
				String zipName = name.replaceAll(".bin", "_sol.zip");
				if (!new File(dir, zipName).exists())
					solFile = true;
			}
			if (!solFile)
				continue;
			if (myNameGreps != null && !myNameGreps.isEmpty()) {
				for (String nameGrep : myNameGreps)
					if (!name.contains(nameGrep))
						continue fileLoop;
			} else if (name.contains("_run") && !assembleMean) {
				// mean solutions allowed, individual runs not allowed
				continue;
			}
			U3LogicTreeBranch branch = VariableLogicTreeBranch.fromFileName(name);
			if (assembleMean) {
				File[] array = files.get(branch);
				if (array == null) {
					array = new File[1];
					array[0] = file;
				} else {
					File[] newArray = new File[array.length+1];
					System.arraycopy(array, 0, newArray, 0, array.length);
					newArray[array.length] = file;
				}
				files.put(branch, array);
			} else {
				checkNoDuplicates(branch, file, files);
				File[] array = { file };
				files.put(branch, array);
			}
		}
		
		return files; 
	}
	
	private static void checkNoDuplicates(
			U3LogicTreeBranch branch, File file, Map<U3LogicTreeBranch, File[]> files) {
		if (files.containsKey(branch)) {
			U3LogicTreeBranch origBranch = null;
			File origFile = files.get(branch)[0];
			for (U3LogicTreeBranch candidateBranch : files.keySet()) {
				if (origFile == files.get(candidateBranch)[0]) {
					origBranch = candidateBranch;
					break;
				}
			}
			String err = "Duplicate branch found!";
			err += "\nOrig branch:\t"+origBranch;
			err += "\nOrig file:\t"+files.get(branch);
			err += "\nNew branch:\t"+branch;
			err += "\nNew file:\t"+file;
			throw new IllegalStateException(err);
		}
	}

	@Override
	public Collection<U3LogicTreeBranch> getBranches() {
		return filesMap.keySet();
	}

	@Override
	protected InversionFaultSystemSolution fetchSolution(U3LogicTreeBranch branch) {
		try {
			File[] files = filesMap.get(branch);
			Arrays.sort(files, new FileNameComparator());
			InversionFaultSystemSolution sol = U3FaultSystemIO.loadInvSol(files[0]);
			if (files.length > 1) {
				List<double[]> ratesList = Lists.newArrayList(sol.getRateForAllRups());
				for (int i=1; i<files.length; i++) {
					double[] rates;
					if (files[i].getName().endsWith(".zip")) {
						ZipFile zip = new ZipFile(files[i]);
						ZipEntry ratesEntry = zip.getEntry("rates.bin");
						rates = MatrixIO.doubleArrayFromInputStream(
								new BufferedInputStream(zip.getInputStream(ratesEntry)), ratesEntry.getSize());
					} else if (files[i].getName().endsWith(".bin")) {
						rates = MatrixIO.doubleArrayFromFile(files[i]);
					} else
						throw new RuntimeException("Wrong file type for solution: "+files[i].getName());
					ratesList.add(rates);
				}
				sol = new U3AverageFaultSystemSolution(sol.getRupSet(), ratesList,
						sol.getInversionConfiguration(), sol.getMisfits());
				sol.setInfoString(sol.getInfoString());
				System.out.println("Built mean with "+ratesList.size()+" sols");
			}
			
			return sol;
		} catch (Exception e) {
			System.err.println("Error check file list dump!");
			System.err.println("\tBRANCH: "+branch);
			File[] files = filesMap.get(branch);
			if (files == null) {
				System.err.println("\tFILES ARE NULL!");
			} else {
				for (int i=0; i<files.length; i++) {
					File file = files[i];
					if (file == null)
						System.err.println("\t"+i+". NULL");
					else
						System.err.println("\t"+i+". "+file.getName());
				}
			}
			throw ExceptionUtils.asRuntimeException(e);
		}
	}

}
