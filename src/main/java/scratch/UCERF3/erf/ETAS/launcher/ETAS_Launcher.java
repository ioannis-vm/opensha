package scratch.UCERF3.erf.ETAS.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.random.RandomDataGenerator;
import org.dom4j.DocumentException;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.util.ClassUtils;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.AbstractERF;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupList;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupture;
import org.opensha.sha.earthquake.observedEarthquake.parsers.UCERF3_CatalogParser;
import org.opensha.sha.earthquake.param.AleatoryMagAreaStdDevParam;
import org.opensha.sha.earthquake.param.ApplyGardnerKnopoffAftershockFilterParam;
import org.opensha.sha.earthquake.param.BPTAveragingTypeOptions;
import org.opensha.sha.earthquake.param.BPTAveragingTypeParam;
import org.opensha.sha.earthquake.param.BackgroundRupParam;
import org.opensha.sha.earthquake.param.BackgroundRupType;
import org.opensha.sha.earthquake.param.HistoricOpenIntervalParam;
import org.opensha.sha.earthquake.param.IncludeBackgroundOption;
import org.opensha.sha.earthquake.param.IncludeBackgroundParam;
import org.opensha.sha.earthquake.param.MagDependentAperiodicityOptions;
import org.opensha.sha.earthquake.param.MagDependentAperiodicityParam;
import org.opensha.sha.earthquake.param.MaximumMagnitudeParam;
import org.opensha.sha.earthquake.param.ProbabilityModelOptions;
import org.opensha.sha.earthquake.param.ProbabilityModelParam;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.io.Files;
import com.google.common.primitives.Floats;

import scratch.UCERF3.FaultSystemRupSet;
import scratch.UCERF3.FaultSystemSolution;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.enumTreeBranches.SpatialSeisPDF;
import scratch.UCERF3.enumTreeBranches.TotalMag5Rate;
import scratch.UCERF3.erf.ETAS.ETAS_CatalogIO;
import scratch.UCERF3.erf.ETAS.ETAS_CubeDiscretizationParams;
import scratch.UCERF3.erf.ETAS.ETAS_EqkRupture;
import scratch.UCERF3.erf.ETAS.ETAS_LocationWeightCalculator;
import scratch.UCERF3.erf.ETAS.ETAS_PrimaryEventSampler;
import scratch.UCERF3.erf.ETAS.ETAS_SimAnalysisTools;
import scratch.UCERF3.erf.ETAS.ETAS_Simulator;
import scratch.UCERF3.erf.ETAS.FaultSystemSolutionERF_ETAS;
import scratch.UCERF3.erf.ETAS.ETAS_Params.ETAS_ParameterList;
import scratch.UCERF3.erf.ETAS.NoFaultsModel.ETAS_Simulator_NoFaults;
import scratch.UCERF3.erf.ETAS.NoFaultsModel.UCERF3_GriddedSeisOnlyERF_ETAS;
import scratch.UCERF3.erf.ETAS.analysis.ETAS_AbstractPlot;
import scratch.UCERF3.erf.ETAS.analysis.SimulationMarkdownGenerator;
import scratch.UCERF3.erf.ETAS.association.FiniteFaultMappingData;
import scratch.UCERF3.erf.utils.ProbabilityModelsCalc;
import scratch.UCERF3.griddedSeismicity.AbstractGridSourceProvider;
import scratch.UCERF3.inversion.InversionFaultSystemSolution;
import scratch.UCERF3.utils.FaultSystemIO;
import scratch.UCERF3.utils.LastEventData;
import scratch.UCERF3.utils.MatrixIO;
import scratch.UCERF3.utils.RELM_RegionUtils;
import scratch.UCERF3.utils.U3_EqkCatalogStatewideCompleteness;

public class ETAS_Launcher {
	
	public enum DebugLevel {
		ERROR(0),
		INFO(1),
		FINE(2),
		DEBUG(3);
		
		private int val;
		private DebugLevel(int val) {
			this.val = val;
		}
		
		boolean shouldPrint(DebugLevel o) {
			return o.val <= val;
		}
	}
	
	protected DebugLevel debugLevel = DebugLevel.FINE;
	
	private ETAS_Config config;
	
	private long simulationOT;
	private String simulationName;
	private File fssFile;
	private Map<TriggerRupture, ETAS_EqkRupture> triggerRupturesMap;
	private List<ETAS_EqkRupture> triggerRuptures;
	private List<ETAS_EqkRupture> histQkList;
	
	// caches
	private double[] gridSeisCorrections;
	private List<float[]> fractionSrcAtPointList;
	private List<int[]> srcAtPointList;
	private int[] isCubeInsideFaultPolygon;
	
	private Deque<FaultSystemSolution> fssDeque = new ArrayDeque<>();
	private Deque<AbstractERF> erfDeque = new ArrayDeque<>();
	
	// last event data
	private Map<Integer, List<LastEventData>> lastEventData;
	private Map<Long, List<Integer>> resetSubSectsMap;
	
	private GriddedRegion griddedRegion;
	private ETAS_CubeDiscretizationParams cubeParams;
	
	private File resultsDir;
	
	private long[] randSeeds;
	private ETAS_ParameterList params;
	
	private boolean dateLastDebug = false;
	
	public ETAS_Launcher(ETAS_Config config) throws IOException {
		this(config, true);
	}
	
	public ETAS_Launcher(ETAS_Config config, boolean mkdirs) throws IOException {
		this(config, mkdirs, config.getRandomSeed());
	}
	
	public ETAS_Launcher(ETAS_Config config, boolean mkdirs, Long randSeed) throws IOException {
		this(config, mkdirs, randSeed, null);
	}
	
	ETAS_Launcher(ETAS_Config config, boolean mkdirs, Long randSeed, ETAS_CubeDiscretizationParams cubeParams) throws IOException {
		ETAS_Simulator.D = false;
		if (config.isGriddedOnly())
			ETAS_Simulator_NoFaults.D = false;
		AbstractGridSourceProvider.SOURCE_MIN_MAG_CUTOFF = 2.55;
		this.config = config;
		
		this.simulationOT = config.getSimulationStartTimeMillis();
		debug(DebugLevel.INFO, "Simulation start time (epoch milliseconds): "+simulationOT);
		debug(DebugLevel.INFO, "Simulation start date: "+SimulationMarkdownGenerator.df.format(new Date(config.getSimulationStartTimeMillis())));
		
		// load ETAS parameters
		params = new ETAS_ParameterList();
		params.setImposeGR(config.isImposeGR());
		params.setU3ETAS_ProbModel(config.getProbModel());
		// already applied if applicable, setting here for metadata
		if (config.isGriddedOnly()) {
			if (config.isGridSeisCorr())
				debug(DebugLevel.INFO, "WARNING: grid seis correction not applied in gridded only case");
			Preconditions.checkState(config.getTotRateScaleFactor() == 1,
					"Total rate scale factor for fault-based ETAS (for now, we don't know the appropriate value for gridded only");
		}
		params.setApplyGridSeisCorr(config.isGridSeisCorr() && !config.isGriddedOnly());
		params.setApplySubSeisForSupraNucl(config.isApplySubSeisForSupraNucl());
		params.setTotalRateScaleFactor(config.getTotRateScaleFactor());
		if (config.getETAS_P() != null) {
			debug(DebugLevel.INFO, "Setting custom p parameter value: "+config.getETAS_P());
			params.set_p(config.getETAS_P());
		}
		if (config.getETAS_C() != null) {
			debug(DebugLevel.INFO, "Setting custom c parameter value: "+config.getETAS_C());
			params.set_c(config.getETAS_C());
		}
		if (config.getETAS_Log10_K() != null) {
			double log10k = config.getETAS_Log10_K();
			double k = Math.pow(10, log10k);
			debug(DebugLevel.INFO, "Setting custom k from Log10(k)="+(float)log10k+": "+k);
			params.set_k(k);
		}
		if (config.getETAS_K_COV() != null) {
			double kCOV = config.getETAS_K_COV();
			debug(DebugLevel.INFO, "Setting k COV: "+(float)kCOV);
			params.set_kCOV(kCOV);
		}
		params.setStatewideCompletenessModel(config.getCompletenessModel());
		
		lastEventData = LastEventData.load();
		resetSubSectsMap = new HashMap<>();
		fssFile = ETAS_Config.resolvePath(config.getFSS_File());
		
		// purge any last event data after OT
		LastEventData.filterDataAfterTime(lastEventData, simulationOT);
		
		// load trigger ruptures
		List<TriggerRupture> triggerRuptureConfigs = config.getTriggerRuptures();
		if (triggerRuptureConfigs != null && !triggerRuptureConfigs.isEmpty()) {
			debug(DebugLevel.INFO, "Building "+triggerRuptureConfigs.size()+" trigger ruptures");
			FaultSystemSolution fss = checkOutFSS();
			FaultSystemRupSet rupSet = fss.getRupSet();
			
			triggerRuptures = new ArrayList<>();
			triggerRupturesMap = new HashMap<>();
			for (TriggerRupture triggerRup : triggerRuptureConfigs) {
				ETAS_EqkRupture rup = triggerRup.buildRupture(rupSet, simulationOT, params);
				triggerRupturesMap.put(triggerRup, rup);
				triggerRuptures.add(rup);
				int[] rupturedSects = triggerRup.getSectionsRuptured(rupSet);
				if (rupturedSects != null && rupturedSects.length > 0) {
					long time = rup.getOriginTime();
					List<Integer> sects = resetSubSectsMap.get(time);
					if (sects == null) {
						sects = new ArrayList<>();
						resetSubSectsMap.put(time, sects);
					}
					for (int sect : rupturedSects)
						sects.add(sect);
				}
			}
			
			checkInFSS(fss);
			
			if (!resetSubSectsMap.isEmpty()) {
				debug(DebugLevel.FINE, "The following subsections' time of occurrence will be reset:");
				for (Long time : getSortedResetTimes())
					debug(DebugLevel.FINE, "\t"+time+": "+Joiner.on(",").join(resetSubSectsMap.get(time)));
			}
		}
		
		// now load a trigger catalog
		histQkList = new ArrayList<>();
		if (config.getTriggerCatalogFile() != null) {
			if (config.getCompletenessModel() == null)
				debug(DebugLevel.INFO, "WARNING: statewide completeness model not specified, using default. "
						+ "Specify with `catalogCompletenessModel` JSON parameter");
			else
				params.setStatewideCompletenessModel(config.getCompletenessModel());
			FaultSystemSolution fss = checkOutFSS();
			try {
				histQkList.addAll(loadHistoricalCatalog(ETAS_Config.resolvePath(config.getTriggerCatalogFile()),
						ETAS_Config.resolvePath(config.getTriggerCatalogSurfaceMappingsFile()),
						fss, simulationOT, resetSubSectsMap, params.getStatewideCompletenessModel()));
			} catch (DocumentException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
			checkInFSS(fss);
		}
		
		Preconditions.checkState(config.isIncludeSpontaneous() || (triggerRuptureConfigs != null && !triggerRuptureConfigs.isEmpty())
				|| !histQkList.isEmpty(), "Empty simulation! Must include spontaneous, trigger ruptures, and/or a trigger catalog");
		
		simulationName = config.getSimulationName();
		if (simulationName == null || simulationName.isEmpty()) {
			if (triggerRuptures != null) {
				if (triggerRuptures.size() > 1) {
					float[] mags = new float[triggerRuptures.size()];
					float maxMag = Float.NEGATIVE_INFINITY;
					for (int i=0; i<mags.length; i++) {
						mags[i] = (float)triggerRuptures.get(i).getMag();
						if (mags[i] > maxMag)
							maxMag = mags[i];
					}
					if (mags.length < 5)
						simulationName = mags.length+" Scenarios (M="+Joiner.on(", M=").join(Floats.asList(mags))+")";
					else
						simulationName = mags.length+" Scenarios (Max="+maxMag+")";
				}
			}
			
			if (!histQkList.isEmpty()) {
				if (simulationName != null)
					simulationName += ", ";
				else
					simulationName = "";
				simulationName += histQkList.size()+" Hist EQs";
			}
			
			if (config.isIncludeSpontaneous()) {
				if (simulationName != null)
					simulationName += ", ";
				else
					simulationName = "";
				simulationName += "Spontaneous";
			}
		}
		
		debug(DebugLevel.FINE, "Simulation name: "+simulationName);
		
		File outputDir = ETAS_Config.resolvePath(config.getOutputDir());
		if (mkdirs)
			waitOnDirCreation(outputDir, 10, 2000);
		
		resultsDir = getResultsDir(outputDir);
		if (mkdirs)
			waitOnDirCreation(resultsDir, 10, 2000);
		
		griddedRegion = RELM_RegionUtils.getGriddedRegionInstance();
		
		if (randSeed == null) {
			randSeed = System.nanoTime();
			debug("determining random seeds with current nano time="+randSeed);
		} else {
			debug("determining random seeds from input seed="+randSeed);
		}
		buildRandomSeeds(randSeed);
		
		if (cubeParams == null)
			cubeParams = new ETAS_CubeDiscretizationParams(griddedRegion);
		this.cubeParams = cubeParams;
	}
	
	static File getResultsDir(File outputDir) {
		return new File(outputDir, "results");
	}
	
	private void buildRandomSeeds(long seed) {
		randSeeds = new long[config.getNumSimulations()];
		if (randSeeds.length == 1) {
			randSeeds[0] = seed;
		} else {
			// seed random number generator with this seed to determine reproducible seeds for all simulations
			RandomDataGenerator r = new RandomDataGenerator();
			r.reSeed(seed);
			for (int i=0; i<config.getNumSimulations(); i++)
				randSeeds[i] = r.nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
		}
	}
	
	void setRandomSeeds(long[] randSeeds) {
		Preconditions.checkState(randSeeds.length == config.getNumSimulations());
		this.randSeeds = randSeeds;
	}
	
	public void debug(String message) {
		debug(DebugLevel.INFO, message);
	}
	
	private static final SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS");
	
	public void debug(DebugLevel level, String message) {
		if (this.debugLevel.shouldPrint(level))
			System.out.println("["+df.format(new Date())+" ("+Thread.currentThread().getName()+")]: "+message);
	}
	
	public void setDebugLevel(DebugLevel level) {
		this.debugLevel = level;
	}
	
	private List<Long> getSortedResetTimes() {
		List<Long> times = new ArrayList<>(resetSubSectsMap.keySet());
		Collections.sort(times);
		return times;
	}
	
	public List<ETAS_EqkRupture> getTriggerRuptures() {
		return triggerRuptures;
	}
	
	public ETAS_EqkRupture getRuptureForTrigger(TriggerRupture trigger) {
		return triggerRupturesMap.get(trigger);
	}

	public List<ETAS_EqkRupture> getHistQkList() {
		return histQkList;
	}

	public FaultSystemSolution checkOutFSS() {
		FaultSystemSolution fss = null;
		synchronized (fssDeque) {
			if (!fssDeque.isEmpty())
				fss = fssDeque.pop();
		}
		if (fss == null) {
			// load a new one
			try {
				debug(DebugLevel.FINE, "Loading a new Fault System Solution from "+fssFile.getAbsolutePath());
				fss = FaultSystemIO.loadSol(fssFile);
				
				if (config.isGridSeisCorr() && !config.isGriddedOnly()) {
					if (gridSeisCorrections == null) {
						synchronized (fssDeque) {
							if (gridSeisCorrections == null) {
								File cacheFile = new File(ETAS_Config.resolvePath(config.getCacheDir()), "griddedSeisCorrectionCache");
								debug(DebugLevel.FINE, "Loading gridded seismicity correction cache file from "+cacheFile.getAbsolutePath());
								gridSeisCorrections = MatrixIO.doubleArrayFromFile(cacheFile);
							}
						}
					}
					ETAS_Simulator.correctGriddedSeismicityRatesInERF(fss, false, gridSeisCorrections);
				}
			} catch (IOException | DocumentException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
		}
		// reset last event data
		FaultSystemRupSet rupSet = fss.getRupSet();
		resetLastEventData(rupSet);
		return fss;
	}

	private void resetLastEventData(FaultSystemRupSet rupSet) {
		if (config.isTimeIndependentERF()) {
			for (int s=0; s<rupSet.getNumSections(); s++)
				rupSet.getFaultSectionData(s).setDateOfLastEvent(Long.MIN_VALUE);
		} else {
			LastEventData.populateSubSects(rupSet.getFaultSectionDataList(), lastEventData);
			
			if (!resetSubSectsMap.isEmpty()) {
				// now apply for any trigger ruptures
				for (Long time : getSortedResetTimes()) {
					for (int s : resetSubSectsMap.get(time))
						rupSet.getFaultSectionData(s).setDateOfLastEvent(time);
				}
			}
		}
	}
	
	public synchronized void checkInFSS(FaultSystemSolution fss) {
		fssDeque.push(fss);
	}
	
	public static List<ETAS_EqkRupture> loadHistoricalCatalog(File catFile, File surfsFile, FaultSystemSolution sol,
			long ot, Map<Long, List<Integer>> resetSubSectsMap, U3_EqkCatalogStatewideCompleteness completenessModel)
					throws IOException, DocumentException {
		List<ETAS_EqkRupture> histQkList = new ArrayList<>();
		// load in historical catalog
		
		Preconditions.checkArgument(catFile.exists(), "Catalog file doesn't exist: "+catFile.getAbsolutePath());
		ObsEqkRupList loadedRups = UCERF3_CatalogParser.loadCatalog(catFile);
		
		if (surfsFile != null) {
			// add rupture surfaces
			FaultModels fm = getFaultModel(sol);
			
			Preconditions.checkArgument(surfsFile.exists(), "Rupture surfaces file doesn't exist: "+surfsFile.getAbsolutePath());
			FiniteFaultMappingData.loadRuptureSurfaces(surfsFile, loadedRups, fm, sol.getRupSet());
		}
		
		// filter for historical completeness
		loadedRups = completenessModel.getFilteredCatalog(loadedRups);
		int numAfter = 0;
		for (ObsEqkRupture rup : loadedRups) {
			if (rup.getOriginTime() > ot) {
				// skip all ruptures that occur after simulation start
				if (numAfter < 10)
					System.out.println("Skipping a M"+rup.getMag()+" after sim start ("
							+rup.getOriginTime()+" > "+ot+"): "+rup);
				numAfter++;
				if (numAfter == 10)
					System.out.println("(supressing future output on skipped ruptures)");
				continue;
			}
			ETAS_EqkRupture etasRup = rup instanceof ETAS_EqkRupture ? (ETAS_EqkRupture)rup : new ETAS_EqkRupture(rup);
			if (etasRup.getFSSIndex() >= 0 && resetSubSectsMap != null) {
				// reset times
				List<Integer> sectIndexes = sol.getRupSet().getSectionsIndicesForRup(etasRup.getFSSIndex());
				System.out.print("Resetting elastic rebound for historical rupture, ot="+etasRup.getOriginTime()+", sects=");
				for (Integer sectIndex : sectIndexes)
					System.out.print(sectIndex+" ");
				System.out.println();
				resetSubSectsMap.put(etasRup.getOriginTime(), sectIndexes);
			}
			etasRup.setID(Integer.parseInt(rup.getEventId()));
			histQkList.add(etasRup);
		}
		System.out.println("Skipped "+numAfter+" ruptures after sim start");
		return histQkList;
	}
	
	/**
	 * A little kludgy, but determines FaultModel by rupture count
	 * @param sol
	 * @return
	 */
	private static FaultModels getFaultModel(FaultSystemSolution sol) {
		int numRups = sol.getRupSet().getNumRuptures();
		if (sol instanceof InversionFaultSystemSolution)
			return ((InversionFaultSystemSolution)sol).getLogicTreeBranch().getValue(FaultModels.class);
		else if (numRups == 253706)
			return FaultModels.FM3_1;
		else if (numRups == 305709)
			return FaultModels.FM3_2;
		else
			throw new IllegalStateException("Don't know Fault Model for solution with "+numRups+" ruptures");
	}
	
	static void waitOnDirCreation(File dir, int maxRetries, long sleepMillis) {
		int retry = 0;
		while (!(dir.exists() || dir.mkdir())) {
			try {
				Thread.sleep(sleepMillis);
			} catch (InterruptedException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
			if (retry++ > maxRetries)
				throw new IllegalStateException("Directory doesn't exist and couldn't be created after "
						+maxRetries+" retries: "+dir.getAbsolutePath());
		}
	}
	
	File getResultsDir(int index) {
		String runName = ""+index;
		int desiredLen = ((config.getNumSimulations()-1)+"").length();
		while (runName.length() < desiredLen)
			runName = "0"+runName;
		runName = "sim_"+runName;
		return new File(resultsDir, runName);
	}
	
	/**
	 * Creates ERF for use with ETAS simulations. Will not be updated
	 * @param sol
	 * @param timeIndep
	 * @param duration
	 * @return
	 */	
	public static FaultSystemSolutionERF_ETAS buildERF(FaultSystemSolution sol, boolean timeIndep, double duration,
			int startYear) {
		long ot = Math.round((startYear-1970.0)*ProbabilityModelsCalc.MILLISEC_PER_YEAR);
		return buildERF_millis(sol, timeIndep, duration, ot);
	}
	
	@SuppressWarnings("unchecked")
	public static FaultSystemSolutionERF_ETAS buildERF_millis(FaultSystemSolution sol, boolean timeIndep, double duration,
			long ot) {
		FaultSystemSolutionERF_ETAS erf = new FaultSystemSolutionERF_ETAS(sol);
		// set parameters
		erf.getParameter(IncludeBackgroundParam.NAME).setValue(IncludeBackgroundOption.INCLUDE);
		erf.setParameter(BackgroundRupParam.NAME, BackgroundRupType.POINT);
		erf.setParameter(ApplyGardnerKnopoffAftershockFilterParam.NAME, false);
		erf.getParameter(ProbabilityModelParam.NAME).setValue(ProbabilityModelOptions.U3_BPT);
		erf.getParameter(MagDependentAperiodicityParam.NAME).setValue(MagDependentAperiodicityOptions.MID_VALUES);
		BPTAveragingTypeOptions aveType = BPTAveragingTypeOptions.AVE_RI_AVE_NORM_TIME_SINCE;
		erf.setParameter(BPTAveragingTypeParam.NAME, aveType);
		erf.setParameter(AleatoryMagAreaStdDevParam.NAME, 0.0);
		if (!timeIndep) {
			double startYear = 1970d + (double)ot/(double)ProbabilityModelsCalc.MILLISEC_PER_YEAR;
			erf.getParameter(HistoricOpenIntervalParam.NAME).setValue(startYear-1875d);
		}
		erf.getTimeSpan().setStartTimeInMillis(ot+1);
		erf.getTimeSpan().setDuration(duration);
		erf.setCacheGridSources(false); // seems faster with set to false, and less memory
		return erf;
	}
	
	public static UCERF3_GriddedSeisOnlyERF_ETAS buildGriddedERF(long ot, double duration) {
		UCERF3_GriddedSeisOnlyERF_ETAS erf = new UCERF3_GriddedSeisOnlyERF_ETAS();
		
		// set parameters
		erf.setParameter(BackgroundRupParam.NAME, BackgroundRupType.POINT);
		erf.setParameter(ApplyGardnerKnopoffAftershockFilterParam.NAME, false);
		erf.setParameter(MaximumMagnitudeParam.NAME, 8.3);
		erf.setParameter("Total Regional Rate",TotalMag5Rate.RATE_7p9);
		erf.setParameter("Spatial Seis PDF",SpatialSeisPDF.UCERF3);

		erf.getTimeSpan().setStartTimeInMillis(ot);
		erf.getTimeSpan().setDuration(duration);
		
		return erf;
	}

	public AbstractERF checkOutERF() {
		AbstractERF erf = null;
		synchronized (erfDeque) {
			if (!erfDeque.isEmpty())
				erf = erfDeque.pop();
		}
		if (erf == null) {
			// load a new one
			debug(DebugLevel.FINE, "Loading a new ERF");
			FaultSystemSolution sol = null;
			if (config.isGriddedOnly()) {
				erf = buildGriddedERF(simulationOT, config.getDuration());
			} else {
				sol = checkOutFSS();
				erf = buildERF_millis(sol, config.isTimeIndependentERF(), config.getDuration(), simulationOT);
			}
			erf.updateForecast();
		} else {
			// already have one, need to reset 
			if (!config.isGriddedOnly() && !config.isTimeIndependentERF()) {
				double startYear = 1970d + (double)simulationOT/(double)ProbabilityModelsCalc.MILLISEC_PER_YEAR;
				erf.getParameter(HistoricOpenIntervalParam.NAME).setValue(startYear-1875d);
			}
			erf.getTimeSpan().setStartTimeInMillis(simulationOT+1);
			erf.getTimeSpan().setDuration(config.getDuration());
			
			if (!config.isGriddedOnly()) {
				// reset all time of last event data
				FaultSystemSolutionERF_ETAS fssERF = (FaultSystemSolutionERF_ETAS)erf;
				FaultSystemRupSet rupSet = fssERF.getSolution().getRupSet();
				resetLastEventData(rupSet);
				for (int s=0; s<rupSet.getNumSections(); s++)
					fssERF.setFltSectOccurranceTime(s, rupSet.getFaultSectionData(s).getDateOfLastEvent());
			}
			
			erf.updateForecast();
		}
		
		return erf;
	}
	
	public void checkInERF(AbstractERF erf) {
		synchronized (erfDeque) {
			erfDeque.push(erf);
		}
	}
	
	public static boolean isAlreadyDone(File resultsDir) {
		File infoFile = new File(resultsDir, "infoString.txt");
		File eventsFile = new File(resultsDir, "simulatedEvents.txt");
		if (!eventsFile.exists())
			eventsFile = new File(resultsDir, "simulatedEvents.bin");
		if (!eventsFile.exists())
			eventsFile = new File(resultsDir, "simulatedEvents.bin.gz");
		if (!infoFile.exists() || !eventsFile.exists() || eventsFile.length() == 0l)
			return false;
		try {
			for (String line : Files.readLines(infoFile, Charset.defaultCharset())) {
				if (line.contains("Total num ruptures: "))
					return true;
			}
		} catch (IOException e) {}
		return false;
	}
	
	private static Long getPrevRandSeed(File resultsDir) throws IOException {
		File infoFile = new File(resultsDir, "infoString.txt");
		if (!infoFile.exists())
			return null;
		for (String line : Files.readLines(infoFile, Charset.defaultCharset())) {
			if (line.contains("randomSeed=")) {
				line = line.trim();
				line = line.substring(line.indexOf("=")+1);
				return Long.parseLong(line);
			}
		}
		return null;
	}
	
	private synchronized void checkLoadCaches() throws IOException {
		if (fractionSrcAtPointList == null) {
			File cacheDir = config.getCacheDir();
			File fractionSrcAtPointListFile = new File(cacheDir, "sectDistForCubeCache");
			File srcAtPointListFile = new File(cacheDir, "sectInCubeCache");
			File isCubeInsideFaultPolygonFile = new File(cacheDir, "cubeInsidePolyCache");
			Preconditions.checkState(fractionSrcAtPointListFile.exists(),
					"cache file not found: "+fractionSrcAtPointListFile.getAbsolutePath());
			Preconditions.checkState(srcAtPointListFile.exists(),
					"cache file not found: "+srcAtPointListFile.getAbsolutePath());
			Preconditions.checkState(isCubeInsideFaultPolygonFile.exists(),
					"cache file not found: "+isCubeInsideFaultPolygonFile.getAbsolutePath());
			debug("loading cache from "+fractionSrcAtPointListFile.getAbsolutePath()+" ("+getMemoryDebug()+")");
			fractionSrcAtPointList = MatrixIO.floatArraysListFromFile(fractionSrcAtPointListFile);
			debug("loading cache from "+srcAtPointListFile.getAbsolutePath()+" ("+getMemoryDebug()+")");
			srcAtPointList = MatrixIO.intArraysListFromFile(srcAtPointListFile);
			debug("loading cache from "+srcAtPointListFile.getAbsolutePath()+" ("+getMemoryDebug()+")");
			isCubeInsideFaultPolygon = MatrixIO.intArrayFromFile(isCubeInsideFaultPolygonFile);
			debug("done loading caches ("+getMemoryDebug()+")");
		}
	}
	
	private String getMemoryDebug() {
		Runtime rt = Runtime.getRuntime();
		long totalMB = rt.totalMemory() / 1024 / 1024;
		long freeMB = rt.freeMemory() / 1024 / 1024;
		long usedMB = totalMB - freeMB;
		return "mem t/u/f: "+totalMB+"/"+usedMB+"/"+freeMB;
	}
	
	private class CalcRunnable implements Callable<Integer> {
		
		private int index;
		private long randSeed;

		public CalcRunnable(int index, long randSeed) {
			Preconditions.checkArgument(index >= 0);
			this.index = index;
			this.randSeed = randSeed;
		}

		@Override
		public Integer call() {
//			System.gc();
			
			File resultsDir = getResultsDir(index);
			if (!config.isForceRecalc() && isAlreadyDone(resultsDir)) {
				debug(index+" is already done: "+resultsDir.getName());
				return index;
			}
			
			waitOnDirCreation(resultsDir, 5, 2000);
			
			debug("calculating "+index);

			debug("Instantiating ERF");
			AbstractERF erf = checkOutERF();
			FaultSystemSolution sol = config.isGriddedOnly() ? null : ((FaultSystemSolutionERF_ETAS)erf).getSolution();
			
			if (index == 0 && dateLastDebug && sol != null) {
				debug(DebugLevel.INFO, "Date of last event information:");
				Map<Long, List<FaultSectionPrefData>> lastEventSects = new HashMap<>();
				for (FaultSectionPrefData sect : sol.getRupSet().getFaultSectionDataList()) {
					Long dateLast = sect.getDateOfLastEvent();
					if (dateLast > Long.MIN_VALUE) {
						List<FaultSectionPrefData> sects = lastEventSects.get(dateLast);
						if (sects == null) {
							sects = new ArrayList<>();
							lastEventSects.put(dateLast, sects);
						}
						sects.add(sect);
					}
				}
				List<Long> allTimes = new ArrayList<>(lastEventSects.keySet());
				Collections.sort(allTimes);
				DateFormat df = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss.SSS z");
				for (Long time : allTimes) {
					GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
					long timeDelta = simulationOT - time;
					double timeDeltaYears = timeDelta / ProbabilityModelsCalc.MILLISEC_PER_YEAR;
					cal.setTimeInMillis(time);
					String timeStr = df.format(cal.getTime());
					debug(DebugLevel.INFO, time+": "+timeStr+" ("
							+ETAS_AbstractPlot.getTimeShortLabel(timeDeltaYears)+" before simulation start)");
					for (FaultSectionPrefData sect: lastEventSects.get(time))
						debug(DebugLevel.INFO, "\t"+sect.getName());
				}
				debug(DebugLevel.INFO, "Sim start: "+simulationOT+": "+df.format(new Date(simulationOT)));
			}

			debug("Done instantiating ERF");
			
			if (config.getRandomSeed() == null) {
				// detect previous random seed to avoid bias towards shorter running jobs in cases
				// with many restarts (as shorter jobs more likely to finish before wall time is up).
				Long prevRandSeed;
				try {
					prevRandSeed = getPrevRandSeed(resultsDir);
				} catch (IOException e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				
				if (prevRandSeed != null) {
					randSeed = prevRandSeed;
					debug("Resuming old rand seed of "+randSeed+" for "+resultsDir.getName());
				}
			}
			
			boolean success = false;
			int attempts = 0;
			Throwable failureThrow = null;
			int retries = config.getNumRetries();
			if (retries < 1)
				retries = 1;
			
			while (!success && attempts < retries) {
				attempts++;
				try {
					if (config.isGriddedOnly()) {
						ETAS_Simulator_NoFaults.runETAS_Simulation(resultsDir, (UCERF3_GriddedSeisOnlyERF_ETAS)erf, griddedRegion,
								triggerRuptures, histQkList, config.isIncludeSpontaneous(), config.isIncludeIndirectTriggering(),
								config.getGridSeisDiscr(), simulationName, randSeed, params, cubeParams);
					} else {
						checkLoadCaches();
						ETAS_Simulator.runETAS_Simulation(resultsDir, (FaultSystemSolutionERF_ETAS)erf, griddedRegion,
								triggerRuptures, histQkList, config.isIncludeSpontaneous(), config.isIncludeIndirectTriggering(),
								config.getGridSeisDiscr(), simulationName, randSeed,
								fractionSrcAtPointList, srcAtPointList, isCubeInsideFaultPolygon, params, cubeParams);
					}
					
					debug("completed "+index);
					if (config.isBinaryOutput()) {
						// convert to binary
						File asciiFile = new File(resultsDir, "simulatedEvents.txt");
						List<ETAS_EqkRupture> catalog = ETAS_CatalogIO.loadCatalog(asciiFile);
						File binaryFile = new File(resultsDir, "simulatedEvents.bin");
						ETAS_CatalogIO.writeCatalogBinary(binaryFile, catalog);
						// make sure that the binary file really succeeded before deleting ascii
						if (binaryFile.length() > 0l)
							asciiFile.delete();
						else
							binaryFile.delete();
						debug("completed binary output "+index);
					}
					success = true;
				} catch (Throwable t) {
					if (t instanceof OutOfMemoryError && exec != null) {
						synchronized (exec) {
							if (!exec.isShutdown() && exec.getMaximumPoolSize() > 1) {
								int newThreads;
								if (exec.getMaximumPoolSize() > 10)
									newThreads = exec.getMaximumPoolSize()-2;
								else
									newThreads = exec.getMaximumPoolSize()-1;
								threadLimit = Integer.max(1, newThreads);
								debug(DebugLevel.ERROR, "Calc ran out of memory, reducing numThreads to "+threadLimit);
								updateExecutorNumThreads(threadLimit);
								throw (OutOfMemoryError)t;
							}
						}
					}
					failureThrow = t;
					debug(DebugLevel.ERROR, "Calc failed with seed "+randSeed+". Exception: "+t);
					t.printStackTrace();
					if (t.getCause() != null) {
						System.err.println("cause exception:");
						t.getCause().printStackTrace();
					}
					System.err.flush();
				}
			}
			
			if (config.isReuseERFs())
				// return this ERF for future use
				checkInERF(erf);
			else if (sol != null)
				// not reusing ERFs, so return the association FSS for reuse
				checkInFSS(sol);
			
			if (!success) {
				Preconditions.checkState(failureThrow != null);
				debug("Index "+index+" failed "+attempts+" times, bailing");
				ExceptionUtils.throwAsRuntimeException(failureThrow);
			}
			
			return index;
		}
		
	}
	
	public static long getMaxMemMB() {
		return Runtime.getRuntime().maxMemory() / 1024 / 1024;
	}
	
	private static int defaultNumThreads() {
		long maxMemMB = getMaxMemMB();
		int maxThreads = (int)(maxMemMB/5000);
		return Integer.max(1, Integer.min(maxThreads, Runtime.getRuntime().availableProcessors()));
	}
	
	public void calculateAll() {
		debug(DebugLevel.FINE, "max mem MB: "+getMaxMemMB());
		int threads = defaultNumThreads();
		debug(DebugLevel.FINE, "max threads calculated from max mem & available procs: "+threads);
		calculateAll(threads);
	}
	
	public void calculateAll(int numThreads) {
		ETAS_BinaryWriter binaryWriter = null;
		if (config.hasBinaryOutputFilters()) {
			debug(DebugLevel.FINE, "initializing binary filter writers");
			try {
				binaryWriter = new ETAS_BinaryWriter(config.getOutputDir(), config);
			} catch (IOException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
		}
		calculate(numThreads, null, binaryWriter);
		if (binaryWriter != null) {
			try {
				debug(DebugLevel.FINE, "finalizing");
				binaryWriter.finalize();
			} catch (IOException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
		}
		shutdownExecutor();
	}
	
	public void calculateBatch(int numThreads, int[] batch) {
		calculate(numThreads, batch, null);
	}
	
	private void updateExecutorNumThreads(int numThreads) {
		if (exec == null)
			return;
		// previous executor, still running
		exec.setCorePoolSize(numThreads);
		exec.setMaximumPoolSize(numThreads);
	}
	
	private int threadLimit = Integer.MAX_VALUE;
	
	private ThreadPoolExecutor exec;
	private ExecutorService getExecutor(int numThreads) {
		if (exec != null && !exec.isShutdown()) {
			// previous executor, still running
			updateExecutorNumThreads(numThreads);
		} else {
			debug(DebugLevel.DEBUG, "building new executor for numThreads="+numThreads);
			exec = new ThreadPoolExecutor(numThreads, numThreads,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>());Executors.newFixedThreadPool(numThreads);
		}
		return exec;
	}
	
	void shutdownExecutor() {
		if (exec != null) {
			debug(DebugLevel.DEBUG, "shutting down executor");
			exec.shutdown();
		}
	}
	
	void calculate(int numThreads, int[] batch, ETAS_BinaryWriter binaryWriter) {
		Stopwatch watch = Stopwatch.createStarted();
		ArrayList<CalcRunnable> tasks = new ArrayList<>();
		
		if (batch != null && batch.length > 0) {
			// we're doing a fixed index/batch
			for (int index : batch)
				tasks.add(new CalcRunnable(index, randSeeds[index]));
		} else {
			for (int index=0; index<config.getNumSimulations(); index++)
				tasks.add(new CalcRunnable(index, randSeeds[index]));
		}
		
		if (numThreads > threadLimit) {
			debug("reducing thread count to previously encountered limit of "+threadLimit);
			numThreads = threadLimit;
		}
		
		debug("starting "+tasks.size()+" simulations with "+numThreads+" threads");
		
		if (numThreads > 1) {
			ExecutorService exec = getExecutor(numThreads);
			
			ArrayDeque<FutureContainer> futures = new ArrayDeque<>();
			
			for (CalcRunnable task : tasks)
				futures.add(new FutureContainer(task, exec.submit(task)));
			
			while (!futures.isEmpty()) {
				FutureContainer future = futures.pop();
				try {
					int index = future.future.get();
					if (binaryWriter != null) {
						debug(DebugLevel.FINE, "processing binary filter for "+index);
						File resultsDir = getResultsDir(index);
						binaryWriter.processCatalog(resultsDir);
					}
				} catch (InterruptedException | ExecutionException | IOException | OutOfMemoryError e) {
					if (e instanceof ExecutionException && e.getCause() instanceof OutOfMemoryError || e instanceof OutOfMemoryError) {
						// we ran out of memory and already reduced thread count
						Preconditions.checkState(threadLimit == 1 || threadLimit < numThreads);
						CalcRunnable task = future.task;
						debug("Resubmitting task "+task.index+" with threadLimit="+threadLimit);
						futures.add(new FutureContainer(task, exec.submit(task)));
					} else {
						exec.shutdownNow();
						if (e instanceof ExecutionException)
							throw ExceptionUtils.asRuntimeException(e.getCause());
						throw ExceptionUtils.asRuntimeException(e);
					}
				}
			}
		} else {
			for (CalcRunnable task : tasks) {
				int index = task.call();
				if (binaryWriter != null) {
					File resultsDir = getResultsDir(index);
					try {
						binaryWriter.processCatalog(resultsDir);
					} catch (IOException e) {
						throw ExceptionUtils.asRuntimeException(e);
					}
				}
			}
		}
		
		watch.stop();
		double secs = watch.elapsed(TimeUnit.MILLISECONDS)/1000d;
		double mins = secs / 60d;
		double hours = mins / 60d;
		String timeStr;
		if (hours > 1.5d)
			timeStr = (float)hours+" hours";
		else if (mins > 1.5d)
			timeStr = (float)mins+" minutes";
		else
			timeStr = (float)secs+" seconds";
		debug("done with "+tasks.size()+" simulations in "+timeStr);
	}
	
	private class FutureContainer {
		CalcRunnable task;
		Future<Integer> future;
		public FutureContainer(CalcRunnable task, Future<Integer> future) {
			super();
			this.task = task;
			this.future = future;
		}
	}
	
	public static List<ETAS_EqkRupture> getFilteredNoSpontaneous(ETAS_Config config, List<ETAS_EqkRupture> catalog) {
		int numTriggerRuptures = config.getTriggerRuptures() == null ? 0 : config.getTriggerRuptures().size();
		if (numTriggerRuptures == 0 && (config.getTriggerCatalogFile() == null || config.isTreatTriggerCatalogAsSpontaneous()))
			// everything in this catalog is spontaneous, can't filter
			return null;
		if (catalog.isEmpty())
			return catalog;
		if (!config.isIncludeSpontaneous() && (config.getTriggerCatalogFile() == null || !config.isTreatTriggerCatalogAsSpontaneous()))
			// does not include any spontaneous ruptures
			return catalog;
		int maxParentID;
		if (config.getTriggerCatalogFile() != null && !config.isTreatTriggerCatalogAsSpontaneous())
			// we have a trigger catalog, and want to include descendants of that catalog
			maxParentID = catalog.get(0).getID()-1;
		else
			// only include descendants of the trigger ruptures
			maxParentID = numTriggerRuptures-1;
		int[] parentIDs = new int[maxParentID+1];
		for (int i=0; i<=maxParentID; i++)
			parentIDs[i] = i;
		return ETAS_SimAnalysisTools.getChildrenFromCatalog(catalog, parentIDs);
	}
	
	private static Options createOptions() {
		Options ops = new Options();

		Option threadsOption = new Option("t", "threads", true,
				"Number of calculation threads. Default is the calculated from max JVM memory (set via -Xmx)" +
						" and the number of available processors (in this case: "+defaultNumThreads()+")");
		threadsOption.setRequired(false);
		ops.addOption(threadsOption);

		Option dateLastDebugOption = new Option("d", "date-last-debug", false,
				"Flag to print out date of last event data for debugging");
		dateLastDebugOption.setRequired(false);
		ops.addOption(dateLastDebugOption);
		
		return ops;
	}

	public static void main(String[] args) throws IOException {
		if (args.length == 1 && args[0].equals("--hardcoded")) {
//			String argsStr = "--date-last-debug --threads 5 /tmp/etas_debug/landers.json";
//			String argsStr = "--date-last-debug --threads 6 /tmp/config.json";
//			String argsStr = "--date-last-debug --threads 6 /tmp/config_noreuse.json";
//			String argsStr = "--threads 1 /home/kevin/OpenSHA/UCERF3/etas/simulations/"
//					+ "2019_07_18-ComCatM7p1_ci38457511_InvertedSurface_ShakeMapSurface"
//					+ "-noSpont-full_td-scale1.14/small/config.json";
			String argsStr = "--threads 1 /home/kevin/OpenSHA/UCERF3/etas/simulations/"
					+ "2019_10_16-Start1919_100yr_Spontaneous_HistoricalCatalog/config.json";
			args = argsStr.split(" ");
		}
		System.setProperty("java.awt.headless", "true");
		
		Options options = createOptions();
		
		CommandLineParser parser = new DefaultParser();
		
		CommandLine cmd;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp(ClassUtils.getClassNameWithoutPackage(ETAS_Launcher.class),
					options, true );
			System.exit(2);
			return;
		}
		
		args = cmd.getArgs();
		
		if (args.length != 1) {
			System.err.println("USAGE: "+ClassUtils.getClassNameWithoutPackage(ETAS_Launcher.class)
					+" [options] <conf-file.json>");
			System.exit(2);
		}
		
		File confFile = new File(args[0]);
		Preconditions.checkArgument(confFile.exists(),
				"configuration file doesn't exist: "+confFile.getAbsolutePath());
		ETAS_Config config = ETAS_Config.readJSON(confFile);
				
		ETAS_Launcher launcher = new ETAS_Launcher(config);
		
		launcher.dateLastDebug = cmd.hasOption("date-last-debug");
		
		if (cmd.hasOption("threads")) {
			int numThreads = Integer.parseInt(cmd.getOptionValue("threads"));
			launcher.calculateAll(numThreads);
		} else {
			launcher.calculateAll();
		}
	}

}
