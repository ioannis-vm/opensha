package org.opensha.sha.simulators.utils;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jfree.data.Range;
import org.opensha.commons.calc.FaultMomentCalc;
import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.Ellsworth_B_WG02_MagAreaRel;
import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.HanksBakun2002_MagAreaRel;
import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.Shaw_2007_MagAreaRel;
import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.WC1994_MagAreaRelationship;
import org.opensha.commons.data.NamedComparator;
import org.opensha.commons.data.function.ArbitrarilyDiscretizedFunc;
import org.opensha.commons.data.function.DiscretizedFunc;
import org.opensha.commons.data.function.EvenlyDiscretizedFunc;
import org.opensha.commons.data.function.DefaultXY_DataSet;
import org.opensha.commons.data.function.HistogramFunction;
import org.opensha.commons.eq.MagUtils;
import org.opensha.commons.geo.Location;
import org.opensha.commons.geo.LocationUtils;
import org.opensha.commons.geo.LocationVector;
import org.opensha.commons.geo.PlaneUtils;
import org.opensha.commons.gui.plot.HeadlessGraphPanel;
import org.opensha.commons.gui.plot.PlotCurveCharacterstics;
import org.opensha.commons.gui.plot.PlotLineType;
import org.opensha.commons.gui.plot.PlotSymbol;
import org.opensha.commons.util.FileUtils;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.FocalMechanism;
import org.opensha.sha.earthquake.calc.recurInterval.BPT_DistCalc;
import org.opensha.sha.earthquake.calc.recurInterval.LognormalDistCalc;
import org.opensha.sha.earthquake.calc.recurInterval.WeibullDistCalc;
import org.opensha.sha.earthquake.rupForecastImpl.WGCEP_UCERF_2_Final.data.finalReferenceFaultParamDb.DeformationModelPrefDataFinal;
import org.opensha.sha.faultSurface.EvenlyGridCenteredSurface;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.faultSurface.StirlingGriddedSurface;
import org.opensha.commons.gui.plot.GraphWindow;
import org.opensha.sha.gui.infoTools.CalcProgressBar;
import org.opensha.sha.imr.param.PropagationEffectParams.DistanceRupParameter;
import org.opensha.sha.magdist.ArbIncrementalMagFreqDist;
import org.opensha.sha.magdist.GutenbergRichterMagFreqDist;
import org.opensha.sha.simulators.SimulatorEvent;
import org.opensha.sha.simulators.EQSIM_Event;
import org.opensha.sha.simulators.EQSIM_EventRecord;
import org.opensha.sha.simulators.EventRecord;
import org.opensha.sha.simulators.RectangularElement;
import org.opensha.sha.simulators.SimulatorElement;
import org.opensha.sha.simulators.Vertex;
import org.opensha.sha.simulators.iden.RuptureIdentifier;
import org.opensha.sha.simulators.parsers.EQSIMv06FileReader;
import org.opensha.sha.simulators.parsers.WardFileReader;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.enumTreeBranches.TotalMag5Rate;
import scratch.UCERF3.erf.utils.ProbModelsPlottingUtils;
import scratch.UCERF3.utils.DeformationModelFetcher;
import scratch.UCERF3.utils.UCERF3_DataUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;

/**
 * This class reads and writes various files, as well as doing some analysis of simulator results.
 * 
 * Note that this class could extend some class representing the "Container" defined in EQSIM, but it's not clear that generality is necessary.
 * 
 * Things to keep in mind:
 * 
 * Indexing in the EQSIM files starts from 1, not zero.  Therefore, here we refer to their "indices" as IDs to avoid confusion
 * (so IDs here start from 1, but indices here start from zero).  Thus, the ID for the ith RectangularElement in rectElementsList 
 * equals i+1 (rectElementsList.get(i).getID() = i+1) because the input file has everything in an order of increasing IDs. The
 * same goes for other lists.
 * 
 * Note that index for eventList is not necessarily equal to eventID-1 because some events 
 * in the input file may not be included (e.g., if Mag = -Inf, which occurs)
 * 
 * All units in EQSIM files are SI
 * 
 * Note that DAS values are unique only within a fault section (values can start over on the next section)
 * 
 * Note that slip rates in EQSIM files are in units of m/s, whereas we convert these to m/yr internally here.
 * 
 * We assume the first vertex in each element here is the first on the upper edge 
 * (traceFlag=2 if the element is at the top); this is not checked for explicitly.
 * 
 * @author field
 *
 */
public class General_EQSIM_Tools {

	protected final static boolean D = false;  // for debugging
	
	private List<SimulatorElement> rectElementsList;
	private List<EQSIM_Event> eventList;
	
	// TODO make all references use the ID/index map
	private List<Double> areaForSections;
	private List<Double> lengthForSections;
	private List<Integer> faultIDs_ForSections;
	private List<Integer> sectionIDs_List;
	private List<String> sectionNamesList;
	private List<Vertex> vertexList;
	private List<List<Vertex>> vertexListForSections;
	/**
	 * map from section ID to index in the area/length lists
	 */
	private Map<Integer, Integer> sectionID_indexMap;
	
	final static String EVENT_FILE_SIG = "EQSim_Output_Event_2";
	final static int EVENT_FILE_SPEC_LEVEL = 2;
	public final static double SECONDS_PER_YEAR = SimulatorUtils.SECONDS_PER_YEAR;
	
//	ArrayList<String> infoStrings;
	String dirNameForSavingFiles;
	
	// not used, and should be lazy initialized if needed
//	UCERF2_DataForComparisonFetcher ucerf2_dataFetcher = new UCERF2_DataForComparisonFetcher();


	/**
	 * This constructor makes the list of RectangularElements from a UCERF2 deformation model
	 * @param deformationModelID	- D2.1 = 82; D2.2 = 83; D2.3 = 84; D2.4 = 85; D2.5 = 86; D2.6 = 87
	 * @param aseisReducesArea		- whether or not to reduce area (otherwise reduces slip rate?)
	 * @param maxDiscretization		- the maximum element size
	 */
	public General_EQSIM_Tools(int deformationModelID, boolean aseisReducesArea, double maxDiscretization) {
		mkElementsFromUCERF2_DefMod(deformationModelID, aseisReducesArea, maxDiscretization);
	}
	
	/**
	 * 
	 * @param faultSections list of fault section pref data
	 * @param aseisReducesArea whether or not to reduce area (otherwise reduces slip rate?)
	 * @param maxDiscretization the maximum element size
	 */
	public General_EQSIM_Tools(List<? extends FaultSection> faultSections, boolean aseisReducesArea,
			double maxDiscretization) {
		init(RectElemFromPrefDataBuilder.build(faultSections, aseisReducesArea, maxDiscretization));
	}
	
	/**
	 * This constructor loads the data from an EQSIM_v04 Geometry file
	 * @param filePathName		 - full path and file name
	 * @throws IOException 
	 */
	public General_EQSIM_Tools(String filePathName) throws IOException {
		init(EQSIMv06FileReader.readGeometryFile(new File(filePathName)));
	}
	
	
	/**
	 * This constructor loads the data from either an EQSIM_v04 Geometry file (formatType=0)
	 * or from Steve Ward's format (formatType=1).
	 * @param filePathName		 - full path and file name
	 * @param formatType		 - set as 0 for EQSIM_v04 Geometry file or 1 for Steve Ward's format
	 * @throws IOException 
	 */
	public General_EQSIM_Tools(String filePathName, int formatType) throws IOException {
		switch (formatType) {
		case 0:
			init(EQSIMv06FileReader.readGeometryFile(new File(filePathName)));
			break;
		case 1:
			init(WardFileReader.readGeometryFile(new File(filePathName)));
			break;

		default:
			throw new IllegalStateException("format type not supported");
		}
	}
	
	/**
	 * This constructor loads the data from an EQSIM_v04 Geometry file
	 * @param url		 - full URL path name
	 * @throws IOException 
	 */
	public General_EQSIM_Tools(File file) throws IOException {
		init(EQSIMv06FileReader.readGeometryFile(file));
	}
	
	/**
	 * This constructor loads the data from an EQSIM_v04 Geometry file
	 * @param url		 - full URL path name
	 * @throws IOException 
	 */
	public General_EQSIM_Tools(URL url) throws IOException {
		init(EQSIMv06FileReader.readGeometryFile(url.openStream()));
	}
	
	public General_EQSIM_Tools(List<SimulatorElement> elements) {
		init(rectElementsList);
	}
	
	private void init(List<SimulatorElement> rectElementsList) {
		// make sure it's sorted by section
		for (int i=1; i<rectElementsList.size(); i++)
			Preconditions.checkState(
					rectElementsList.get(i-1).getSectionID() <= rectElementsList.get(i).getSectionID(),
					"Elements not sorted by section ID increasing");
		
		this.rectElementsList = rectElementsList;
		
		// populate lists
		areaForSections = Lists.newArrayList();
		lengthForSections = Lists.newArrayList();
		sectionIDs_List = Lists.newArrayList();
		sectionNamesList = Lists.newArrayList();
		faultIDs_ForSections = Lists.newArrayList();
		vertexList = Lists.newArrayList();
		vertexListForSections = Lists.newArrayList();
		sectionID_indexMap = Maps.newHashMap();
		
		int prevSectID = -1;
		int prevFaultID = -1;
		double curArea = 0d;;
		double curDASMin = Double.POSITIVE_INFINITY, curDASMax = 0d;
		String curName = null;
		List<Vertex> vertexListForSection = null;
		for (SimulatorElement elem : rectElementsList) {
			int sectID = elem.getSectionID();
			Preconditions.checkState(sectID >= 0);
			if (sectID != prevSectID) {
				if (prevSectID > 0) {
					Preconditions.checkState(!sectionID_indexMap.containsKey(prevSectID));
					sectionID_indexMap.put(prevSectID, areaForSections.size());
					areaForSections.add(curArea);
					lengthForSections.add(curDASMax - curDASMin);
					sectionIDs_List.add(prevSectID);
					sectionNamesList.add(curName);
					faultIDs_ForSections.add(prevFaultID);
					vertexListForSections.add(vertexListForSection);
				}
				curArea = 0;
				curDASMax = 0;
				curDASMin = Double.POSITIVE_INFINITY;
				prevSectID = sectID;
				curName = elem.getSectionName();
				prevFaultID = elem.getFaultID();
				vertexListForSection = Lists.newArrayList();
			}
			curArea += elem.getArea();
			curDASMin = Math.min(curDASMin, elem.getMinDAS()*1000d); // convert back to m
			curDASMax = Math.max(curDASMax, elem.getMaxDAS()*1000d); // convert back to m
			for (Vertex v : elem.getVertices()) {
				vertexList.add(v);
				vertexListForSection.add(v);
			}
		}
		if (curArea > 0) {
			Preconditions.checkState(!sectionID_indexMap.containsKey(prevSectID));
			sectionID_indexMap.put(prevSectID, areaForSections.size());
			areaForSections.add(curArea);
			lengthForSections.add(curDASMax - curDASMin);
			sectionIDs_List.add(prevSectID);
			sectionNamesList.add(curName);
			faultIDs_ForSections.add(prevFaultID);
			vertexListForSections.add(vertexListForSection);
		}
	}
	
	public List<String> getSectionsNameList() {
		return sectionNamesList;
	}
	
	public void setEvents(List<EQSIM_Event> eventList) {
		this.eventList = eventList;
	}
	
	/**
	 * This tells whether the given event is a supra-seismogenic rupture.
	 * If magThresh is not NaN, this returns true if event mag is >= magThresh
	 * If magThresh is NaN, this returns true sqrt(rupArea) >= (aveFaultDownDipWidth-1.5 km),
	 * where the 1.5 km buffer is half an element width.
	 * @param event
	 * @param magThresh - set as Double.NaN to use down-dip-width
	 * @return
	 */
	public boolean isEventSupraSeismogenic(SimulatorEvent event, double magThresh) {
		boolean supraSeis = false;
		if(Double.isNaN(magThresh)) {	// apply sqrt(area) > aveDDW
			double totArea = 0;
			double totLength = 0;
			for(EventRecord evRec:event) {
				int sectIndex = sectionID_indexMap.get(evRec.getSectionID());
				double ddwForSect = areaForSections.get(sectIndex)/lengthForSections.get(sectIndex);
				double lengthOnRec = evRec.getLength();	// length of rup on section
				totLength += lengthOnRec;
				totArea += lengthOnRec*ddwForSect;
//System.out.println("\tsectIndex="+sectIndex+"\tlengthOnRec="+(float)lengthOnRec+"\tddwForSect="+(float)ddwForSect);
			}
			double aveFltDDW = totArea/totLength;
			Preconditions.checkState(!Double.isNaN(aveFltDDW), "NaN! area="+totArea+", len="+totLength);
//System.out.println("aveFltDDW="+(float)aveFltDDW+"\tevent.getArea()="+(float)event.getArea()+"\tsqrt(event.getArea())="+(float)Math.sqrt(event.getArea()));
//System.out.println(event.toString());

			if(Math.sqrt(event.getArea()) >= (aveFltDDW-1500.0))	// subtract 1.5 km (half element length) from the latter
				supraSeis = true;
		}
		else {
			if(event.getMagnitude() >= magThresh)
				supraSeis = true;
		}
		return supraSeis;
	}
	
	
	/**
	 * This returns the list of RectangularElement objects
	 * @return
	 */
	public List<SimulatorElement> getElementsList() { return rectElementsList; }
	
	
	public String printMinAndMaxElementArea() {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for(SimulatorElement re:getElementsList()) {
			double area = re.getArea();
			if(area<min) min=area;
			if(area>max) max=area;
		}
		String info = "min element area (km) = "+(float)(min*1e-6)+"; max element area (km) = "+(float)(max*1e-6)+"\n";
		System.out.println(info);
		return info;
	}
	
	
	/**
	 * This tests whether element areas agree between gridded surface and that computed by RectangularElement
	 */
	public void testElementAreas() {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		int el_index = 0;
		for(SimulatorElement re:getElementsList()) {
			double area1 = re.getArea()*1e-6;
			if(area1<min) min=area1;
			if(area1>max) max=area1;
			double area2 = re.getSurface().getArea();
			double ratio = area1/area2;
			if(ratio >1.01 || ratio < 0.99)
				System.out.println(el_index+"\t"+ratio+"\t"+area1+"\t"+area2);
			el_index += 1;
		}
		System.out.println("min element area (km) = "+(float)(min*1e-6));
		System.out.println("max element area (km) = "+(float)(max*1e-6));
	}
	
	
	
	
	/**
	 * This tests the EQSIM_Event.getDistAlongRupForElements() for a few ruptures, and assuming the
	 * "eqs.ALLCAL2_RSQSim_sigma0.5-5_b=0.015.barall" input file is used (event indices are set by hand).
	 * The data file was loaded into Igor and plotted to confirm the distance along rupture looked good
	 * (but note ambiguities when ruptures fork).
	 */
	public void testDistanceAlong() {
		
//		int testEventID = 161911;	// NSAF
//		int testEventID = 168;			// SSAF
		int testEventID = 192778;		// Calaveras and others
		EQSIM_Event testEvent = getEventsHashMap().get(testEventID);
		int numSect = testEvent.size();
		System.out.println("numSect="+numSect);
		
		ArrayList<SimulatorElement> elementList = testEvent.getAllElements();
		double[] distAlongArray = testEvent.getNormDistAlongRupForElements();
		FileWriter fw;
		try {
			fw = new FileWriter("tempDistAlongTest.txt");
			System.out.println("numElem="+distAlongArray.length);
			for(int i=0;i<distAlongArray.length;i++) {
				Location loc = elementList.get(i).getCenterLocation();
				fw.write((float)loc.getLatitude()+"\t"+(float)loc.getLongitude()+"\t"+(float)loc.getDepth()+"\t"+(float)distAlongArray[i]+"\n");
			}
			fw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}


	
	/**
	 * This makes the elements from a UCERF2 deformation model
	 * @param deformationModelID	- D2.1 = 82; D2.2 = 83; D2.3 = 84; D2.4 = 85; D2.5 = 86; D2.6 = 87
	 * @param aseisReducesArea		- whether or not to reduce area (otherwise reduces slip rate?)
	 * @param maxDiscretization		- the maximum element size
	 */
	public void mkElementsFromUCERF2_DefMod(int deformationModelID, boolean aseisReducesArea, 
			double maxDiscretization) {
		
		// fetch the sections
		DeformationModelPrefDataFinal deformationModelPrefDB = new DeformationModelPrefDataFinal();
		List<FaultSectionPrefData> allFaultSectionPrefData = deformationModelPrefDB.getAllFaultSectionPrefData(deformationModelID);
		
		List<SimulatorElement> elems = RectElemFromPrefDataBuilder.build(
				allFaultSectionPrefData, aseisReducesArea, maxDiscretization);
		init(elems);
	}
	
	/**
	 * This computes the total magnitude frequency distribution (with an option to plot the results)
	 * @return
	 */
	public ArbIncrementalMagFreqDist computeTotalMagFreqDist(double minMag, double maxMag, int numMag, 
			boolean makePlot, boolean savePlot) {
		ArbIncrementalMagFreqDist mfd = new ArbIncrementalMagFreqDist(minMag, maxMag, numMag);
		
		double simDurr = getSimulationDurationYears();
		for(SimulatorEvent event : eventList) {
			mfd.addResampledMagRate(event.getMagnitude(), 1.0/simDurr, true);
		}
		mfd.setName("Total Simulator Incremental Mag Freq Dist");
		mfd.setInfo("  ");
				
		if(makePlot){
			ArrayList<DiscretizedFunc> mfdList = new ArrayList<DiscretizedFunc>();
			mfdList.add(mfd);
			mfdList.add(mfd.getCumRateDistWithOffset());
			mfdList.get(1).setName("Total Simulator Cumulative Mag Freq Dist");
			mfdList.get(1).setInfo(" ");
			
			double totRate = TotalMag5Rate.RATE_7p9.getRateMag5();
			GutenbergRichterMagFreqDist grDist = new GutenbergRichterMagFreqDist(1.0, totRate, 5.05, 9.95, 50);
			EvenlyDiscretizedFunc cumGR = grDist.getCumRateDistWithOffset();
			cumGR.setName("Perfect GR with total rate = "+totRate);
			cumGR.setInfo("");
			mfdList.add(cumGR);
//			// get observed MFDs from UCERF2	
//			mfdList.add(UCERF2_DataForComparisonFetcher.getHalf_UCERF2_ObsIncrMFDs(true));
//			mfdList.addAll(UCERF2_DataForComparisonFetcher.getHalf_UCERF2_ObsCumMFDs(true));
			ArrayList<PlotCurveCharacterstics> curveChar = new ArrayList<PlotCurveCharacterstics>();
//			Color pink = new Color(255, 127, 127);
			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.BLACK));
			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.RED));
			curveChar.add(new PlotCurveCharacterstics(PlotLineType.DASHED, 2f, Color.GRAY));
//			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 1f, pink));
//			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 1f, pink));
//			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 1f, pink));

			GraphWindow graph = new GraphWindow(mfdList, "Total Mag Freq Dist", curveChar); 
			graph.setX_AxisLabel("Magnitude");
			graph.setY_AxisLabel("Rate (per yr)");
			graph.setX_AxisRange(4.5, 8.5);
			double yMin = 1e-6;
			double yMax = 2e1;
			graph.setY_AxisRange(yMin,yMax);
			graph.setYLog(true);

			if(savePlot)
				try {
					graph.saveAsPDF(dirNameForSavingFiles+"/TotalMagFreqDist.pdf");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}


		}

		return mfd;
	}
	
		
// 

	/**
	 * This returns a list of incremental MFDs reflecting the rates of nucleation (as a function of mag) 
	 * on each fault section.  It also optionally makes plots.
	 */
	/**
	 * @param minMag
	 * @param maxMag
	 * @param numMag
	 * @param makeOnePlotWithAll - plot all incremental dists in one graph
	 * @param makeSeparatePlots - make separate plots of incremental and cumulative distributions
	 * @return
	 */
	public ArrayList<ArbIncrementalMagFreqDist> computeMagFreqDistByFaultSection(double minMag, double maxMag, int numMag, 
			boolean makeOnePlotWithAll, boolean makeSeparatePlots, boolean savePlots) {
		
		ArrayList<ArbIncrementalMagFreqDist> mfdList = new ArrayList<ArbIncrementalMagFreqDist>();
		for(int s=0; s<sectionNamesList.size(); s++) {
			ArbIncrementalMagFreqDist mfd = new ArbIncrementalMagFreqDist(minMag, maxMag, numMag);
			mfd.setName(sectionNamesList.get(s)+" Incremental MFD");
			mfd.setInfo(" ");
			mfdList.add(mfd);
		}
		
		double simDurr = getSimulationDurationYears();
		for(SimulatorEvent event : eventList) {
			int sectionIndex = sectionID_indexMap.get(event.get(0).getSectionID());	// nucleates on first (0th) event record 
			mfdList.get(sectionIndex).addResampledMagRate(event.getMagnitude(), 1.0/simDurr, true);
		}
		
		double yMin = Math.pow(10,Math.floor(Math.log10(1/getSimulationDurationYears())));
		if(makeOnePlotWithAll){
			GraphWindow graph = new GraphWindow(mfdList, "Mag Freq Dists (Incremental)");   
			graph.setX_AxisLabel("Magnitude");
			graph.setY_AxisLabel("Rate (per yr)");
			graph.setX_AxisRange(4.5, 8.5);
			double yMax = graph.getY_AxisRange().getUpperBound();
			if(yMin<yMax) {
				graph.setY_AxisRange(yMin,yMax);
				graph.setYLog(true);
			}
			if(savePlots)
				try {
					graph.saveAsPDF(dirNameForSavingFiles+"/MagFreqDistForAllSections.pdf");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

		}
		
		if(makeSeparatePlots) {
			int sectNum=-1;
			for(ArbIncrementalMagFreqDist mfd :mfdList) {
				sectNum +=1;
				ArrayList<EvenlyDiscretizedFunc> mfdList2 = new ArrayList<EvenlyDiscretizedFunc>();
				mfdList2.add(mfd);
				mfdList2.add(mfd.getCumRateDistWithOffset());
				mfdList2.get(1).setName(sectionNamesList.get(sectNum)+" Cumulative MFD");
				mfdList2.get(1).setInfo(" ");
				GraphWindow graph = new GraphWindow(mfdList2, sectionNamesList.get(sectNum)+" MFD"); 
				graph.setX_AxisLabel("Magnitude");
				graph.setY_AxisLabel("Rate (per yr)");
				graph.setX_AxisRange(4.5, 8.5);
				double yMax = graph.getY_AxisRange().getUpperBound();
				if(yMin<yMax) {
					graph.setY_AxisRange(yMin,yMax);
					graph.setYLog(true);
				}
				if(savePlots)
					try {
						graph.saveAsPDF(dirNameForSavingFiles+"/MagFreqDistFor"+sectionNamesList.get(sectNum)+".pdf");
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

			}
		}
		
		return mfdList;
	}

	
	/**
	 * This tells whether all events have data on the slip on each element
	 * @return
	 */
	public int getNumEventsWithElementSlipData() {
		int numTrue =0;
		for (SimulatorEvent event : eventList) {
			if(event.hasElementSlipsAndIDs()) numTrue +=1;
		}
		return numTrue;
	}
	
	
	/**
	 * Note that because some events were filtered out, the index in the returned array list
	 * is not neccessariy equal to the eventID minus one (getEventsList().get(index).getID()=1 != index).
	 * @return
	 */
	public List<EQSIM_Event> getEventsList() {
		return eventList;
	}
	
	/**
	 * This returns a HashMap of EQSIM_Event objects where the key is the event ID
	 * @return
	 */
	public Map<Integer, EQSIM_Event> getEventsHashMap() {
		HashMap<Integer,EQSIM_Event> map = new HashMap<Integer,EQSIM_Event>();
		for(EQSIM_Event event:getEventsList()) {
			map.put(event.getID(), event);
		}
		return map;
	}
	
	
	/**
	 * This replaces all the event times with a random value sampled between
	 * the first and last original event time using a uniform distribution.
	 */
	public void randomizeEventTimes() {
		System.out.println("Event Times have been randomized");
		double firstEventTime=eventList.get(0).getTime();
		double simDurInSec = eventList.get(eventList.size()-1).getTime() - firstEventTime;
		for(SimulatorEvent event:eventList)
			event.setTime(firstEventTime+Math.random()*simDurInSec);
		Collections.sort(eventList);
	}
	
	
	/**
	 * This plots yearly event rates
	 */
	public void plotYearlyEventRates() {
		
		double startTime=eventList.get(0).getTime();
		int numYears = (int)getSimulationDurationYears();
		EvenlyDiscretizedFunc evPerYear = new EvenlyDiscretizedFunc(0.0, numYears+1, 1.0);
		for(SimulatorEvent event :eventList) {
			int year = (int)((event.getTime()-startTime)/SECONDS_PER_YEAR);
			evPerYear.add(year, 1.0);
		}
		ArrayList<EvenlyDiscretizedFunc> funcList = new ArrayList<EvenlyDiscretizedFunc>();
		funcList.add(evPerYear);
		GraphWindow graph = new GraphWindow(funcList, "Num Events Per Year"); 
		graph.setX_AxisLabel("Year");
		graph.setY_AxisLabel("Number");
	}
	
	
	public void plotSAF_EventsAlongStrikeVsTime(double magThresh, int maxNumEvents, int safFaultID) {
		ArrayList<ArbitrarilyDiscretizedFunc> funcList = new ArrayList<ArbitrarilyDiscretizedFunc>();

		int numSAF_Events=0;
		for(EQSIM_Event event: eventList) {
			if(isEventSupraSeismogenic(event, magThresh) && doesEventUtilizedFault(event, 1)) {
				numSAF_Events += 1;
				if(numSAF_Events > maxNumEvents)
					break;
				// compute min and max das on SAF
				double minDAS = Double.MAX_VALUE;
				double maxDAS = Double.NEGATIVE_INFINITY;
//				for(EventRecord rec:event) {
				for (int i=0; i<event.size(); i++) {
					EQSIM_EventRecord rec = event.get(i);
					int sectIndex = sectionID_indexMap.get(rec.getSectionID());
					if(faultIDs_ForSections.get(sectIndex) == 1) {
						if(rec.getMinDAS() < minDAS) 
							minDAS = rec.getMinDAS();
						if(rec.getMaxDAS() > maxDAS) 
							maxDAS = rec.getMaxDAS();
					}
				}
				ArbitrarilyDiscretizedFunc func = new ArbitrarilyDiscretizedFunc();
				func.set(minDAS/1000,event.getTimeInYears());
				func.set(maxDAS/1000,event.getTimeInYears());
				funcList.add(func);
			}
		}
		GraphWindow graph = new GraphWindow(funcList, "SAF Events"); 
		graph.setX_AxisLabel("Distance Along Strike (km)");
		graph.setY_AxisLabel("Number");

	}
	
	
	public static HistogramFunction getNormRI_Distribution(ArrayList<Double> normRI_List, double deltaT) {
		// find max value
		double max=0;
		for(Double val:normRI_List)
			if(val>max) max = val;
//		double deltaX=0.1;
		int num = (int)Math.ceil(max/deltaT)+2;
// System.out.println("HERE: "+num+"\t"+max+"\t"+deltaT);
		HistogramFunction dist = new HistogramFunction(deltaT/2,num,deltaT);
		dist.setTolerance(2*deltaT);
		int numData=normRI_List.size();
		for(Double val:normRI_List) {
//System.out.println(val);
			dist.add(val, 1.0/(numData*deltaT));  // this makes it a true PDF
		}
		return dist;
	}
	
	
	public static ArrayList<EvenlyDiscretizedFunc> getRenewalModelFunctionFitsToDist(EvenlyDiscretizedFunc dist) {
		// now make the function list for the plot
		ArrayList<EvenlyDiscretizedFunc> funcList = new ArrayList<EvenlyDiscretizedFunc>();
		
		// add best-fit BPT function
		BPT_DistCalc bpt_calc = new BPT_DistCalc();
		bpt_calc.fitToThisFunction(dist, 0.5, 1.5, 11, 0.1, 1.5, 151);
		EvenlyDiscretizedFunc fitBPT_func = bpt_calc.getPDF();
		fitBPT_func.setName("Best Fit BPT Dist");
		fitBPT_func.setInfo("(mean="+(float)bpt_calc.getMean()+", aper="+(float)bpt_calc.getAperiodicity()+")");
		funcList.add(fitBPT_func);
		
		// add best-fit Lognormal dist function
		LognormalDistCalc logNorm_calc = new LognormalDistCalc();
		logNorm_calc.fitToThisFunction(dist, 0.5, 1.5, 11, 0.1, 1.5, 141);
		EvenlyDiscretizedFunc fitLogNorm_func = logNorm_calc.getPDF();
		fitLogNorm_func.setName("Best Fit Lognormal Dist");
		fitLogNorm_func.setInfo("(mean="+(float)logNorm_calc.getMean()+", aper="+(float)logNorm_calc.getAperiodicity()+")");
		funcList.add(fitLogNorm_func);
		
		// add best-fit Weibull dist function
		WeibullDistCalc weibull_calc = new WeibullDistCalc();
		weibull_calc.fitToThisFunction(dist, 0.5, 1.5, 11, 0.1, 1.5, 141);
		EvenlyDiscretizedFunc fitWeibull_func = weibull_calc.getPDF();
		fitWeibull_func.setName("Best Fit Weibull Dist");
		fitWeibull_func.setInfo("(mean="+(float)weibull_calc.getMean()+", aper="+(float)weibull_calc.getAperiodicity()+")");
		funcList.add(fitWeibull_func);

		return funcList;
	}
	
	/**
	 * This utility returns a graph panel with a histogram of normalized recurrence intervals 
	 * from normRI_List, along with best-fit BPT, Lognormal, and Weibull distributions.
	 * 
	 * 	 * This is an alternative version of plotNormRI_Distribution(*)

	 * @param normRI_List
	 * @param plotTitle
	 */
	public static HeadlessGraphPanel getNormRI_DistributionGraphPanel(ArrayList<Double> normRI_List, String plotTitle) {
		
		// get the normalized RI dist
		double delta=0.1;
		HistogramFunction dist = getNormRI_Distribution(normRI_List, delta);
		
		// now make the list of best-fit functions for the plot
		ArrayList<EvenlyDiscretizedFunc> funcList = getRenewalModelFunctionFitsToDist(dist);
				
		// add the histogram created here
		dist.setName("Recur. Int. Dist");
		dist.setInfo("Number of points = "+ normRI_List.size()+"\nMean="+dist.computeMean()+"\nCOV="+dist.computeCOV());
		funcList.add(dist);
		
		ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.BLACK));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.BLUE));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.GREEN));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM, 2f, Color.RED));
		
		HeadlessGraphPanel gp = new HeadlessGraphPanel();
		gp.setUserBounds(new Range(0, 5), null);
		gp.drawGraphPanel("RI (yrs)", "Density", funcList, curveCharacteristics, plotTitle);
		gp.getChartPanel().setSize(1000, 800);
		
		return gp;
	}
	
	/**
	 * This utility plots the histogram of normalized recurrence intervals from normRI_List, 
	 * along with best-fit BPT, Lognormal, and Weibull distributions.  Use the returned object
	 * if you want to save to a file.
	 * This is an alternative version of getNormRI_DistributionGraphPanel(*)
	 * @param normRI_List
	 * @param plotTitle
	 * @param bptAperForComparison - this will add a BPT dist with mean=1 and given aper for comparison (set as Double.NaN if not desired)
	 */
	public static GraphWindow plotNormRI_Distribution(ArrayList<Double> normRI_List, String plotTitle, double bptAperForComparison) {
		
		// get the normalized RI dist
		double delta=0.1;
		HistogramFunction dist = getNormRI_Distribution(normRI_List, delta);
		// add the histogram created here
		dist.setName("Recur. Int. Dist");
		String info = "Number of points = "+ normRI_List.size()+ "\nComputed mean = "+(float)dist.computeMean()+
				"\nComputed COV = "+(float)dist.computeCOV();
		dist.setInfo(info);
		
		ArrayList<EvenlyDiscretizedFunc> funcList = new ArrayList<EvenlyDiscretizedFunc>();
		funcList.add(dist);
		
		// now make the list of best-fit functions for the plot
		funcList.addAll(getRenewalModelFunctionFitsToDist(dist));
						
		ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM, 2f, Color.RED));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.BLACK));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.BLUE));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.GREEN));
		
		if(!Double.isNaN(bptAperForComparison)) {
			BPT_DistCalc bpt_calc = new BPT_DistCalc();
			bpt_calc.setAll(1.0, bptAperForComparison, funcList.get(1).getDelta()/2, funcList.get(1).size());	// not the first one because that's the obs histogram
			EvenlyDiscretizedFunc bpt_func = bpt_calc.getPDF();
			bpt_func.setName("BPT Dist for comparison");
			bpt_func.setInfo("(mean="+1.0+", aper="+bptAperForComparison+")");
			funcList.add(bpt_func);
			curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.GRAY));
		}
		
		// make plot
		GraphWindow graph = new GraphWindow(funcList, plotTitle, curveCharacteristics); 
		graph.setX_AxisLabel("RI (yrs)");
		graph.setY_AxisLabel("Density");
		graph.setX_AxisRange(0, 5);
		graph.setY_AxisRange(0, 2.3);
		graph.setAxisLabelFontSize(22);
		graph.setTickLabelFontSize(20);
		graph.setPlotLabelFontSize(22);
		return graph;
		
	}
	
	
	public void testTemp() {
		 System.out.println(isEventSupraSeismogenic(eventList.get(314951), Double.NaN));
	}

	
	
	/**
	 * This method evaluates Ned's average time- and slip-predictability in various ways.
	 * @param supraSeisMagThresh - threshold defining supra-seismogenic rupture (if Double.NaN sqrt(area)>fltDDW will be used)
	 * @param saveStuff
	 * @param testElementID - set null if not wanted
	 * @return info string
	 */
	public String testTimePredictability(double supraSeisMagThresh, boolean saveStuff, Integer testElementID, boolean plotSectionResults) {
		
		String linesFor_fw_timePred = new String();
		String tempInfoString = new String();
		
		double[] lastTimeForElement = new double[rectElementsList.size()];
		double[] lastSlipForElement = new double[rectElementsList.size()];
		for(int i=0; i<lastTimeForElement.length;i++) 
			lastTimeForElement[i]=Double.NaN;  // initialize to bogus value so we can check


		// first get average RI and ave slip for each element
		double[] aveRI_ForElement = new double[rectElementsList.size()];
		double[] aveSlipForElement = new double[rectElementsList.size()];
		int[] numEventsForElement = new int[rectElementsList.size()];
		for(SimulatorEvent event:eventList) {
			double eventTime = event.getTime();
			if(event.hasElementSlipsAndIDs() && isEventSupraSeismogenic(event, supraSeisMagThresh)) {  
				double[] slips = event.getAllElementSlips();
				int[] elemIDs = event.getAllElementIDs();
				int numElements = slips.length;
				for(int e=0;e<numElements;e++) {
					int index = elemIDs[e]-1;  // index = ID-1
					aveSlipForElement[index] += slips[e];
					numEventsForElement[index] += 1;
					double lastTime = lastTimeForElement[index];
					if(!Double.isNaN(lastTime)) {
						aveRI_ForElement[index] += (eventTime-lastTime);
					}
					lastTimeForElement[index] = eventTime;
				}
			}
		}
		for(int i=0; i<aveRI_ForElement.length; i++) {
			aveRI_ForElement[i] /= numEventsForElement[i]-1;	// one less than the number of events; sum is just equal to last minus first time
			aveSlipForElement[i] /= numEventsForElement[i];
			aveRI_ForElement[i] /= SECONDS_PER_YEAR;
		}
			
			
		// reinitialize array
		for(int i=0; i<lastTimeForElement.length;i++) 
			lastTimeForElement[i]=-1;  // initialize to bogus value so we can check

		int numBad=0;
		double minElementArea = Double.MAX_VALUE;
		double maxElementArea = 0;
		int counter=-1;
			
		ArrayList<Double> obsIntervalList = new ArrayList<Double>();
		ArrayList<Double> tpInterval1List = new ArrayList<Double>();
		ArrayList<Double> tpInterval2List = new ArrayList<Double>();
		ArrayList<Double> spInterval1List = new ArrayList<Double>();
		ArrayList<Double> spInterval2List = new ArrayList<Double>();
		ArrayList<Double> aveSlipRateList = new ArrayList<Double>();
		ArrayList<Double> aveElementIntervalList = new ArrayList<Double>();
		ArrayList<Double> norm_aveElementIntervalList = new ArrayList<Double>();	    // normalized by long-term RI averaged over all elements
		ArrayList<Double> norm_aveElementIntervalLowMagList = new ArrayList<Double>();	    // for below M≤6.7
		ArrayList<Double> norm_aveElementIntervalMidMagList = new ArrayList<Double>();	    // for 6.7<M≤7.7
		ArrayList<Double> norm_aveElementIntervalHighMagList = new ArrayList<Double>();	    // for M>7.7
		ArrayList<Double> norm_aveElementIntervalAlt1_List = new ArrayList<Double>();	// normalized by one-over long-term rate averaged over all elements
		ArrayList<Double> norm_aveElementIntervalAlt2_List = new ArrayList<Double>();	// ave normalized element RI
		ArrayList<Double> norm_aveElementIntervalAlt2_LowMagList = new ArrayList<Double>();	    // for below M≤6.7
		ArrayList<Double> norm_aveElementIntervalAlt2_MidMagList = new ArrayList<Double>();	    // for 6.7<M≤7.7
		ArrayList<Double> norm_aveElementIntervalAlt2_HighMagList = new ArrayList<Double>();	    // for M>7.7
	
		
		ArrayList<Double> norm_tpInterval1List = new ArrayList<Double>();
		ArrayList<Double> norm_spInterval1List = new ArrayList<Double>();
		ArrayList<Double> norm_tpInterval2List = new ArrayList<Double>();
		ArrayList<Double> norm_spInterval2List = new ArrayList<Double>();
		ArrayList<Double> norm_lastEventSlipList = new ArrayList<Double>();	// normalized by long-term ave slip over all elements
		ArrayList<Double> norm_nextEventSlipList = new ArrayList<Double>();	// normalized by long-term ave slip over all elements

		ArrayList<Integer> nucleationSectionList = new ArrayList<Integer>();	// this saves which section each event nucleated on
			
		// these are for an element-specific analysis (e.g., to see if tp and sp are correlated at the element given in the constructor)
		boolean eventUtilizesTestElement = false;
		ArrayList<Double> tpInterval2ListForTestElement = new ArrayList<Double>();
		ArrayList<Double> spInterval2ListForTestElement = new ArrayList<Double>();
			
		tempInfoString+="supraSeisMagThresh = "+supraSeisMagThresh+"\n";
			
		// write file header
		linesFor_fw_timePred+="counter\tobsInterval\ttpInterval1\tnorm_tpInterval1\ttpInterval2\tnorm_tpInterval2\t"+
						"spInterval1\tnorm_spInterval1\tspInterval2\tnorm_spInterval2\tnorm_aveElementInterval\tnorm_aveElementIntervalAlt1\tnorm_aveElementIntervalAlt2\t"+
						"aveLastSlip\taveSlip\tnorm_lastEventSlip\tnorm_nextEventSlip\teventMag\teventID\tfirstSectionID\tnumSectionsInEvent\tsectionsInEventString\n";
			
		// for norm RI along rupture
		double startNormDistAlong = 0.0125;
		double deltaNormDistAlong = 0.025;
		int numDistAlong = 20;
		HistogramFunction aveNormRI_AlongRup = new HistogramFunction(startNormDistAlong, numDistAlong,deltaNormDistAlong);
		HistogramFunction numRIsAlongHist = new HistogramFunction(startNormDistAlong, numDistAlong,deltaNormDistAlong);
		// for the full distributions along rupture
		HistogramFunction[] riDistsAlongAlongRup = new HistogramFunction[numDistAlong];
		for(int i=0;i<numDistAlong;i++)
//			riDistsAlongAlongRup[i] = new HistogramFunction(-1, 31, 0.1);	// log10 space
			riDistsAlongAlongRup[i] = new HistogramFunction(0.05, 50, 0.1);

		CalcProgressBar progressBar = new CalcProgressBar("testTimePredictability","Events Processed");
		progressBar.showProgress(true);
		
		int eventNum=-1;
		
		// loop over all events
		for(EQSIM_Event event:eventList) {
			double eventTime = event.getTime();
			
			eventNum +=1;
			progressBar.updateProgress(eventNum, eventList.size());
			
			if(event.hasElementSlipsAndIDs() && isEventSupraSeismogenic(event, supraSeisMagThresh)) {  
				boolean goodSample = true;
				double eventMag = event.getMagnitude();
				String sectionsInEventString = new String();
				double[] slips = event.getAllElementSlips();
				int[] elemIDs = event.getAllElementIDs();
				double[] normDistAlong = event.getNormDistAlongRupForElements();
				
				// compile list of sections involved
				for(EventRecord evRec: event) {
					if(eventTime != evRec.getTime()) throw new RuntimeException("problem with event times");  // just a check
					sectionsInEventString += sectionNamesList.get(sectionID_indexMap.get(evRec.getSectionID())) + " + ";
				}
				// get average date of last event and average predicted date of next
				double aveLastEvTime=0;
				double ave_tpNextEvTime=0;
				double ave_spNextEvTime=0;
				double aveSlipRate =0;
				double aveLastSlip =0;
				double aveEventSlip=0;
				double aveSlipOverElements=0;	// the average of long-term ave slips
				double aveElementInterval=0;			// the long-term RI averaged over all elements
				double aveElementIntervalFromRates=0;	// this will be one over the ave long-term element rates
				double aveNormTimeSinceLast=0;
				int numElementsUsed = 0;
				int numNormDistProblems = 0;
				for(int e=0;e<slips.length;e++) {
					int index = elemIDs[e]-1;  // index = ID-1
					double lastTime = lastTimeForElement[index];
					double lastSlip = lastSlipForElement[index];
					double slipRate = rectElementsList.get(index).getSlipRate();
					double area = rectElementsList.get(index).getArea();
					if(area<minElementArea) minElementArea = area;
					if(area>maxElementArea) maxElementArea = area;
					if(slipRate != 0) {  // there are a few of these, and I don't know what else to do other than ignore
						aveLastEvTime += lastTime;
						ave_tpNextEvTime += lastTime + lastSlip/(slipRate/SECONDS_PER_YEAR);
						ave_spNextEvTime += lastTime + slips[e]/(slipRate/SECONDS_PER_YEAR);
						aveSlipRate += slipRate/SECONDS_PER_YEAR;
						aveLastSlip += lastSlip;
						aveEventSlip += slips[e];
						aveElementInterval += aveRI_ForElement[index];
						aveElementIntervalFromRates += 1.0/aveRI_ForElement[index];
						aveSlipOverElements += aveSlipForElement[index];
						aveNormTimeSinceLast += ((eventTime-lastTime)/SECONDS_PER_YEAR)/aveRI_ForElement[index];
						numElementsUsed += 1;
						
						// for ave norm RI along rupture
//						if(lastTime != -1 && doesEventUtilizedFault(event, 1)) {	// 1 is for SAF sections
						if(lastTime != -1 && normDistAlong != null) {	// latter avoids Ward events where some records are missing slips
							double normRI_forElement = ((eventTime-lastTime)/SECONDS_PER_YEAR)/aveRI_ForElement[index];
							double distFromEnd = normDistAlong[e];
							if(distFromEnd>0.5) distFromEnd = 1.0-distFromEnd;
							int distAlongIndex = aveNormRI_AlongRup.getXIndex(distFromEnd);
							if(distAlongIndex == -1) {
								numNormDistProblems+=1;
								break;
							}
								
							HistogramFunction hist = riDistsAlongAlongRup[distAlongIndex];
							int xIndexForHist = hist.getClosestXIndex(normRI_forElement);
//							int xIndexForHist = hist.getClosestXIndex(Math.log10(normRI_forElement));
//							if(xIndexForHist  != -1)
								hist.add(xIndexForHist, 1.0);
//							else
//								System.out.println("normRI_forElement excluded: "+normRI_forElement);
							
							aveNormRI_AlongRup.add(distFromEnd,normRI_forElement);
							numRIsAlongHist.add(distFromEnd,1.0);
						}
					}
					
					// mark as bad sample if  lastTime is -1
					if(lastTime==-1){
						goodSample=false;
						//							System.out.println("time=0 for element"+e+" of event"+eventNum);
					}
				}
				
				
				if(numNormDistProblems>0) tempInfoString += "WARNING! - Problem with NormalizedSlipAlongRup.pdf (norm distance outside bounds for "+numNormDistProblems+" events)\n";

				aveLastEvTime /= numElementsUsed;
				ave_tpNextEvTime /= numElementsUsed;
				ave_spNextEvTime /= numElementsUsed;
				aveSlipRate /= numElementsUsed;
				aveLastSlip /= numElementsUsed;
				aveEventSlip /= numElementsUsed;
				aveElementInterval /= numElementsUsed;
				aveElementIntervalFromRates = numElementsUsed/aveElementIntervalFromRates;
				aveSlipOverElements /= numElementsUsed;
				aveNormTimeSinceLast /= numElementsUsed;
				double obsInterval = (eventTime-aveLastEvTime)/SECONDS_PER_YEAR;
				double tpInterval1 = (ave_tpNextEvTime-aveLastEvTime)/SECONDS_PER_YEAR;
				double tpInterval2 = (aveLastSlip/aveSlipRate)/SECONDS_PER_YEAR;
				double spInterval1 = (ave_spNextEvTime-aveLastEvTime)/SECONDS_PER_YEAR;
				double spInterval2 = (aveEventSlip/aveSlipRate)/SECONDS_PER_YEAR;
				double norm_tpInterval1 = obsInterval/tpInterval1;		// note the ratio is opposite of a true normalization
				double norm_tpInterval2 = obsInterval/tpInterval2;
				double norm_spInterval1 = obsInterval/spInterval1;
				double norm_spInterval2 = obsInterval/spInterval2;
				double norm_aveElementInterval = obsInterval/aveElementInterval;
				double norm_aveElementIntervalAlt1 = obsInterval/aveElementIntervalFromRates;
				double norm_aveElementIntervalAlt2 = aveNormTimeSinceLast;
				double norm_lastEventSlip = aveLastSlip/aveSlipOverElements;
				double norm_nextEventSlip = aveEventSlip/aveSlipOverElements;
				
if(norm_tpInterval1 < 0  && goodSample) {
	System.out.println("obsInterval="+obsInterval);
	System.out.println("tpInterval1="+tpInterval1);
	System.out.println("tpInterval2="+tpInterval2);
	System.out.println("ave_tpNextEvTime="+ave_tpNextEvTime);
	System.out.println("aveLastEvTime="+aveLastEvTime);
	System.out.println("numElements="+numElementsUsed);
	System.out.println("elemIDs\teventID\teventMag\teventArea\ttpInterval1\tlastTime\tlastSlip\tslipRate\t(lastSlip/slipRate)\tsectsInEvent");

	for(int e=0;e<numElementsUsed;e++) {
		int index = elemIDs[e]-1;  // index = ID-1
		double lastTime = lastTimeForElement[index];
		double lastSlip = lastSlipForElement[index];
		double slipRate = rectElementsList.get(index).getSlipRate();
		System.out.println(elemIDs[e]+"\t"+event.getID()+"\t"+eventMag+"\t"+event.getArea()+"\t"+tpInterval1+"\t"+lastTime+"\t"+lastSlip+"\t"+
				(float)slipRate+"\t"+ (float)(lastSlip/slipRate)+"\t"+sectionsInEventString);
//			ave_tpNextEvTime += lastTime + lastSlip/(slipRate/SECONDS_PER_YEAR);
	}

	throw new RuntimeException("norm_tpInterval1 is negative: "+norm_tpInterval1);
}

				// skip those that have zero aveSlipRate (causes Inf for tpInterval2 &spInterval2)
				if(aveSlipRate == 0) goodSample = false;

				// set boolean for whether event utilizes test element
				if(testElementID != null) {
					if(Ints.contains(elemIDs, testElementID))
						eventUtilizesTestElement=true;
					else
						eventUtilizesTestElement=false;
				}

				if(goodSample) {
					counter += 1;
					linesFor_fw_timePred+=counter+"\t"+obsInterval+"\t"+
						tpInterval1+"\t"+(float)norm_tpInterval1+"\t"+
						tpInterval2+"\t"+(float)norm_tpInterval2+"\t"+
						spInterval1+"\t"+(float)norm_spInterval1+"\t"+
						spInterval2+"\t"+(float)norm_spInterval2+"\t"+
						(float)norm_aveElementInterval+"\t"+
						(float)norm_aveElementIntervalAlt1+"\t"+
						(float)norm_aveElementIntervalAlt2+"\t"+
						(float)aveLastSlip+"\t"+(float)aveEventSlip+"\t"+
						(float)norm_lastEventSlip+"\t"+(float)norm_nextEventSlip+"\t"+
						(float)eventMag+"\t"+event.getID()+"\t"+
						event.get(0).getSectionID()+"\t"+
						event.size()+"\t"+sectionsInEventString+"\n";
					// save for calculating correlations
					obsIntervalList.add(obsInterval);
					tpInterval1List.add(tpInterval1);
					tpInterval2List.add(tpInterval2);
					spInterval1List.add(spInterval1);
					spInterval2List.add(spInterval2);
					aveSlipRateList.add(aveSlipRate);
					aveElementIntervalList.add(aveElementInterval);
					nucleationSectionList.add(event.get(0).getSectionID());

					norm_aveElementIntervalList.add(norm_aveElementInterval);
					// separate out by magnitude
					if(eventMag<=6.7)
						norm_aveElementIntervalLowMagList.add(norm_aveElementInterval);
					else if(eventMag<=7.7)
						norm_aveElementIntervalMidMagList.add(norm_aveElementInterval);
					else
						norm_aveElementIntervalHighMagList.add(norm_aveElementInterval);
					
					if(norm_aveElementIntervalAlt1 < 50)	// found a problem case for ESQSim with seismogenic cutoff of 6.5
						norm_aveElementIntervalAlt1_List.add(norm_aveElementIntervalAlt1);
					else {
						norm_aveElementIntervalAlt1_List.add(50d);	// cap at 50
						System.out.println("Strange norm_aveElementIntervalAlt1: "+norm_aveElementIntervalAlt1+"\naveElementIntervalFromRates="+
								aveElementIntervalFromRates+"\n Element RIs:");
						for(int e=0;e<slips.length;e++) {
							int index = elemIDs[e]-1;  // index = ID-1
							double slipRate = rectElementsList.get(index).getSlipRate();
							if(slipRate != 0)
								System.out.println("\t"+e+"\t"+aveRI_ForElement[index]);
						}
					}
					
					norm_aveElementIntervalAlt2_List.add(norm_aveElementIntervalAlt2);
					// separate out by magnitude
					if(eventMag<=6.7)
						norm_aveElementIntervalAlt2_LowMagList.add(norm_aveElementIntervalAlt2);
					else if(eventMag<=7.7)
						norm_aveElementIntervalAlt2_MidMagList.add(norm_aveElementIntervalAlt2);
					else
						norm_aveElementIntervalAlt2_HighMagList.add(norm_aveElementIntervalAlt2);

					norm_tpInterval1List.add(norm_tpInterval1);
					norm_spInterval1List.add(norm_spInterval1);
					norm_tpInterval2List.add(norm_tpInterval2);
					norm_spInterval2List.add(norm_spInterval2);
					norm_lastEventSlipList.add(norm_lastEventSlip);
					norm_nextEventSlipList.add(norm_nextEventSlip);

					// add to test element list if it's the right element
					if(testElementID != null) {
						if(eventUtilizesTestElement) {
							tpInterval2ListForTestElement.add(tpInterval2);
							spInterval2ListForTestElement.add(spInterval2);
						}						
					}

					if(obsInterval<1.0) { // less then one year
						String str = "Short Interval (less than 1 yr):\t"+obsInterval+" yrs) for eventID="+event.getID()+
						"; mag="+(float)eventMag+"; timeYrs="+(float)event.getTimeInYears()+"\n";
						tempInfoString += str;
					}


				}
				else {
//					System.out.println("event "+ eventNum+" is bad");
					numBad += 1;
				}

				// now fill in the last event data for next time
				for(int e=0;e<numElementsUsed;e++) {
					int index = elemIDs[e]-1;
					lastTimeForElement[index] = eventTime;
					lastSlipForElement[index] = slips[e];
				}
			}
		}
		
		progressBar.showProgress(false);

//		// plot the normalized distributions and best fits
//		HeadlessGraphPanel plot1 = getNormRI_DistributionGraphPanel(norm_tpInterval1List, "Normalized Ave Time-Pred RI (norm_tpInterval1List)");
//		HeadlessGraphPanel plot2 = getNormRI_DistributionGraphPanel(norm_spInterval1List, "Normalized Ave Slip-Pred RI (norm_spInterval1List)");			
//		HeadlessGraphPanel plot3 = getNormRI_DistributionGraphPanel(norm_tpInterval2List, "Normalized Ave Time-Pred RI (norm_tpInterval2List)");
//		HeadlessGraphPanel plot4 = getNormRI_DistributionGraphPanel(norm_spInterval2List, "Normalized Ave Slip-Pred RI (norm_spInterval2List)");			
//		HeadlessGraphPanel plot5 = getNormRI_DistributionGraphPanel(norm_aveElementIntervalList, "Normalized Obs to Ave Element RI (norm_aveElementIntervalList)");			
//		HeadlessGraphPanel plot6 = getNormRI_DistributionGraphPanel(norm_lastEventSlipList, "Normalized Ave Slip (X-axis is mislabeled; should be normalized slip)");	// both lists are the same(?)		
//		if(saveStuff) {
//			try {
//				plot1.saveAsPDF(dirNameForSavingFiles+"/norm_tpInterval1_Dist.pdf");
//				plot2.saveAsPDF(dirNameForSavingFiles+"/norm_spInterval1_Dist.pdf");
//				plot3.saveAsPDF(dirNameForSavingFiles+"/norm_tpInterval2_Dist.pdf");
//				plot4.saveAsPDF(dirNameForSavingFiles+"/norm_spInterval2_Dist.pdf");
//				plot5.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementInterval_Dist.pdf");
//				plot6.saveAsPDF(dirNameForSavingFiles+"/norm_aveSlip_Dist.pdf");
//			} catch (IOException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		}
		
//		System.out.println("norm_aveElementIntervalAlt1_List.size()="+norm_aveElementIntervalAlt1_List.size());
		
		// plot the normalized distributions and best fits
		GraphWindow plot1 = plotNormRI_Distribution(norm_tpInterval1List, "Normalized Ave Time-Pred RI (norm_tpInterval1List)", Double.NaN);
		GraphWindow plot2 = plotNormRI_Distribution(norm_spInterval1List, "Normalized Ave Slip-Pred RI (norm_spInterval1List)", Double.NaN);			
		GraphWindow plot3 = plotNormRI_Distribution(norm_tpInterval2List, "Normalized Ave Time-Pred RI (norm_tpInterval2List)", Double.NaN);
		GraphWindow plot4 = plotNormRI_Distribution(norm_spInterval2List, "Normalized Ave Slip-Pred RI (norm_spInterval2List)", Double.NaN);			
		GraphWindow plot6 = plotNormRI_Distribution(norm_lastEventSlipList, "Normalized Ave Slip (X-axis is mislabeled; should be normalized slip)", Double.NaN);	// both lists are the same(?)
		
		
		GraphWindow plot5 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalList, Double.NaN), "Normalized Rup RI (ave elem RIs; norm_aveElementIntervalList)");
		GraphWindow plot7 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalAlt1_List, Double.NaN), "Normalized Rup RI (ave elem rates; norm_aveElementIntervalAlt1_List)");
		GraphWindow plot8 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalAlt2_List, Double.NaN), "Normalized Rup RI (ave norm elem RIs; norm_aveElementIntervalAlt2_List)");

		GraphWindow plot13 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalLowMagList, Double.NaN), "M<=6.7 Norm Rup RI");
		GraphWindow plot14 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalMidMagList, Double.NaN), "6.7<M<=7.7 Norm Rup RI");
		GraphWindow plot15 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalHighMagList, Double.NaN), "M>7.7 Norm Rup RI");
		GraphWindow plot16 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalAlt2_LowMagList, Double.NaN), "M<=6.7 Norm Rup RI; Alt2");
		GraphWindow plot17 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalAlt2_MidMagList, Double.NaN), "6.7<M<=7.7 Norm Rup RI; Alt2");
		GraphWindow plot18 = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(norm_aveElementIntervalAlt2_HighMagList, Double.NaN), "M>7.7 Norm Rup RI; Alt2");

//		GraphWindow plot5 = plotNormRI_Distribution(norm_aveElementIntervalList, "Normalized Rup RI (ave elem RIs; norm_aveElementIntervalList)", Double.NaN);			
//		GraphWindow plot7 = plotNormRI_Distribution(norm_aveElementIntervalAlt1_List, "Normalized Rup RI (ave elem rates; norm_aveElementIntervalAlt1_List)", Double.NaN);			
//		GraphWindow plot8 = plotNormRI_Distribution(norm_aveElementIntervalAlt2_List, "Normalized Rup RI (ave norm elem RIs; norm_aveElementIntervalAlt2_List)", Double.NaN);			

		if(saveStuff) {
			try {
				plot1.saveAsPDF(dirNameForSavingFiles+"/norm_tpInterval1_Dist.pdf");
				plot2.saveAsPDF(dirNameForSavingFiles+"/norm_spInterval1_Dist.pdf");
				plot3.saveAsPDF(dirNameForSavingFiles+"/norm_tpInterval2_Dist.pdf");
				plot4.saveAsPDF(dirNameForSavingFiles+"/norm_spInterval2_Dist.pdf");
				plot5.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementInterval_Dist.pdf");
				plot5.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementInterval_Dist.txt");
				plot6.saveAsPDF(dirNameForSavingFiles+"/norm_aveSlip_Dist.pdf");
				plot7.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementInterval_DistAlt1.pdf");
				plot7.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementInterval_DistAlt1.txt");
				plot8.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementInterval_DistAlt2.pdf");
				plot8.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementInterval_DistAlt2.txt");
				
				plot13.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalLowMag_Dist.pdf");
				plot13.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalLowMag_Dist.txt");
				plot14.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalMidMag_Dist.pdf");
				plot14.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalMidMag_Dist.txt");
				plot15.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalHighMag_Dist.pdf");
				plot15.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalHighMag_Dist.txt");
				
				plot16.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_LowMag_Dist.pdf");
				plot16.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_LowMag_Dist.txt");
				plot17.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_MidMag_Dist.pdf");
				plot17.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_MidMag_Dist.txt");
				plot18.saveAsPDF(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_HighMag_Dist.pdf");
				plot18.saveAsTXT(dirNameForSavingFiles+"/norm_aveElementIntervalAt2_HighMag_Dist.txt");

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}


		// Print correlations for "observed" and predicted intervals
		double[] result;
		tempInfoString +="\nCorrelations (and chance it's random) between all Observed and Predicted Intervals:\n\n";
		result = this.getCorrelationAndP_Value(aveElementIntervalList, obsIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for aveElementInterval (num pts ="+tpInterval1List.size()+")\n";
		result = this.getCorrelationAndP_Value(tpInterval1List, obsIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for tpInterval1 (num pts ="+tpInterval1List.size()+")\n";
		result = this.getCorrelationAndP_Value(tpInterval2List, obsIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for tpInterval2 (num pts ="+tpInterval2List.size()+")\n";
		result = this.getCorrelationAndP_Value(spInterval1List, obsIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for spInterval1 (num pts ="+spInterval1List.size()+")\n";
		result = this.getCorrelationAndP_Value(spInterval2List, obsIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for spInterval2 (num pts ="+spInterval2List.size()+")\n";

		tempInfoString +="\nCorrelations (and chance it's random) for true time and slip predictability tests:\n\n";
		result = this.getCorrelationAndP_Value(norm_lastEventSlipList, norm_aveElementIntervalList);	
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for norm_lastEventSlip vs norm_aveElementInterval (num pts ="+norm_lastEventSlipList.size()+")\n";
		result = this.getCorrelationAndP_Value(norm_nextEventSlipList, norm_aveElementIntervalList);
		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for norm_nextEventSlip vs norm_aveElementInterval (num pts ="+norm_nextEventSlipList.size()+")\n";

		// Print correlations between time-pred. and slip-pred. intervals - not meaningful because slip rate variation causes these to correlate
		// this should really be correlation between last and next slip (normalized by last slip)
//		tempInfoString +="\nCorrelations (and chance it's random) between all Predicted Intervals:\n";
//		result = this.getCorrelationAndP_Value(tpInterval1List, spInterval1List);
//		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for tpInterval1 vs spInterval1List (num pts ="+tpInterval1List.size()+")\n";
//		result = this.getCorrelationAndP_Value(tpInterval2List, spInterval2List);
//		tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for tpInterval2 vs spInterval2List (num pts ="+tpInterval2List.size()+")\n";

		// plot normalized obs interval vs normalized last slip correlation
		result = getCorrelationAndP_Value(norm_lastEventSlipList, norm_aveElementIntervalList);
		String info1 = "correlation="+(float)result[0]+"\t("+(float)result[1]+") for norm_aveElementIntervalList vs norm_lastEventSlipList \n";
		DefaultXY_DataSet xy_data1 = new DefaultXY_DataSet(norm_lastEventSlipList, norm_aveElementIntervalList);
		xy_data1.setName("norm_aveElementIntervalList vs norm_lastEventSlipList");
		xy_data1.setInfo(info1);
		GraphWindow graph1 = new GraphWindow(xy_data1, "Norm Obs RI vs Norm Last Slip");   
		graph1.setX_AxisLabel("Normalized Last-Event Slip");
		graph1.setY_AxisLabel("Normalized Recurrence Interval");
		graph1.setAllLineTypes(null, PlotSymbol.CROSS);
		graph1.setAxisRange(0.1, 10, 0.1, 10);
		graph1.setYLog(true);
		graph1.setXLog(true);
		if(saveStuff) {
			try {
				graph1.saveAsPDF(dirNameForSavingFiles+"/normObsRI_vsNormLastSlip.pdf");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// plot normalized obs interval vs normalized next slip correlation
		result = getCorrelationAndP_Value(norm_nextEventSlipList, norm_aveElementIntervalList);
		String info2 = "correlation="+(float)result[0]+"\t("+(float)result[1]+") for norm_aveElementIntervalList vs norm_nextEventSlipList \n";
		DefaultXY_DataSet xy_data2 = new DefaultXY_DataSet(norm_nextEventSlipList, norm_aveElementIntervalList);
		xy_data2.setName("norm_aveElementIntervalList vs norm_nextEventSlipList");
		xy_data2.setInfo(info2);
		GraphWindow graph2 = new GraphWindow(xy_data2, "Norm Obs RI vs Norm Next Slip");   
		graph2.setX_AxisLabel("Normalized Next-Event Slip");
		graph2.setY_AxisLabel("Normalized Recurrence Interval");
		graph2.setAllLineTypes(null, PlotSymbol.CROSS);
		graph2.setAxisRange(0.1, 10, 0.1, 10);
		graph2.setYLog(true);
		graph2.setXLog(true);
		if(saveStuff) {
			try {
				graph2.saveAsPDF(dirNameForSavingFiles+"/normObsRI_vsNormNextSlip.pdf");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// now do correlations for each section (by where it nucleates)
		tempInfoString +="\nCorrelations (and chance it's random) between: Observed & aveElementInterval, Observed & tpInterval2, Observed & spInterval2, normObserved & normAveLastSlip, and normObserved & normAveNextSlip by Section:\n\n";
		tempInfoString +="\tsectID\taveElRI\tprobRand\ttpInt2\tprobRand\tspInt2\tprobRand\tnormObsVsNormLastSlip\tprobRand\tnormObsVsNormNextSlip\tprobRand\tsectName\tsectID\tnumPts\n";
		ArrayList<DefaultXY_DataSet> obs_tp2_funcs = new ArrayList<DefaultXY_DataSet>();
		HashMap<Integer,DefaultXY_DataSet> obs_tp2_funcsMap = new HashMap<Integer,DefaultXY_DataSet>();
		ArrayList<DefaultXY_DataSet> obs_aveElement_funcs = new ArrayList<DefaultXY_DataSet>();
		HashMap<Integer,DefaultXY_DataSet> obs_aveElement_funcsMap = new HashMap<Integer,DefaultXY_DataSet>();
		ArrayList<DefaultXY_DataSet> normObs_vs_normLastSlip_funcs = new ArrayList<DefaultXY_DataSet>();
		HashMap<Integer,DefaultXY_DataSet> normObs_vs_normLastSlip_funcsMap = new HashMap<Integer,DefaultXY_DataSet>();
		
		for(int s=0;s<sectionNamesList.size();s++) {
			ArrayList<Double> obsVals = new ArrayList<Double>();
			ArrayList<Double> tpVals = new ArrayList<Double>();
			ArrayList<Double> spVals = new ArrayList<Double>();
			ArrayList<Double> aveElVals = new ArrayList<Double>();
			ArrayList<Double> normObsVals = new ArrayList<Double>();
			ArrayList<Double> normLastSlipVals = new ArrayList<Double>();
			ArrayList<Double> normNextSlipVals = new ArrayList<Double>();
			for(int i=0;i<obsIntervalList.size();i++) {
				if(nucleationSectionList.get(i).intValue() == s+1) {
					obsVals.add(obsIntervalList.get(i));
					tpVals.add(tpInterval2List.get(i));
					spVals.add(spInterval2List.get(i));
					aveElVals.add(aveElementIntervalList.get(i));
					normObsVals.add(norm_aveElementIntervalList.get(i));
					normLastSlipVals.add(norm_lastEventSlipList.get(i));
					normNextSlipVals.add(norm_nextEventSlipList.get(i));
				}
			}
			if(obsVals.size()>2) {
				double[] result0 = getCorrelationAndP_Value(obsVals, aveElVals);
				double[] result1 = getCorrelationAndP_Value(obsVals, tpVals);
				double[] result2 = getCorrelationAndP_Value(obsVals, spVals);
				double[] result3 = getCorrelationAndP_Value(normObsVals, normLastSlipVals);
				double[] result4 = getCorrelationAndP_Value(normObsVals, normNextSlipVals);
				String info = "\t"+(s+1)+"\t"+(float)result0[0]+"\t"+(float)result0[1]+"\t"+
											  (float)result1[0]+"\t"+(float)result1[1]+"\t"+
											  (float)result2[0]+"\t"+(float)result2[1]+"\t"+
											  (float)result3[0]+"\t"+(float)result3[1]+"\t"+
											  (float)result4[0]+"\t"+(float)result4[1]+"\t"+
											  sectionNamesList.get(s)+"\t"+(s+1)+"\t"+obsVals.size()+"\n";
				tempInfoString +=info;
				
				// make XY data for plot
				DefaultXY_DataSet xy_data0 = new DefaultXY_DataSet(aveElVals,obsVals);
				xy_data0.setName(sectionNamesList.get(s));
				xy_data0.setInfo("sectID = "+(s+1)+"\tcorr = "+(float)result0[0]+"\t("+(float)result0[1]+")\t");
				obs_aveElement_funcs.add(xy_data0);
				obs_aveElement_funcsMap.put(s, xy_data0);
				
				DefaultXY_DataSet xy_data = new DefaultXY_DataSet(tpVals,obsVals);
				xy_data.setName(sectionNamesList.get(s));
				xy_data.setInfo("sectID = "+(s+1)+"\tcorr = "+(float)result1[0]+"\t("+(float)result1[1]+")\t");
				obs_tp2_funcs.add(xy_data);
				obs_tp2_funcsMap.put(s, xy_data);
				
				DefaultXY_DataSet xy_data5 = new DefaultXY_DataSet(normLastSlipVals,normObsVals);
				xy_data5.setName(sectionNamesList.get(s));
				xy_data5.setInfo("sectID = "+(s+1)+"\tcorr = "+(float)result3[0]+"\t("+(float)result3[1]+")\t");
				normObs_vs_normLastSlip_funcs.add(xy_data5);
				normObs_vs_normLastSlip_funcsMap.put(s, xy_data5);

			}
			else
				tempInfoString +="\t"+(s+1)+"\tNaN\t\t\t\t\t\t\t\t"+
				sectionNamesList.get(s)+" (num points = "+obsVals.size()+")\n";
		}
		GraphWindow graph0 = new GraphWindow(obs_aveElement_funcs, "Obs vs Ave Element RI");   
		graph0.setX_AxisLabel("Ave Element RI (aveElementInterval) (years)");
		graph0.setY_AxisLabel("Observed RI (years)");
		graph0.setAllLineTypes(null, PlotSymbol.CROSS);
		graph0.setYLog(true);
		graph0.setXLog(true);
		GraphWindow graph = new GraphWindow(obs_tp2_funcs, "Obs vs Time-Pred RIs");   
		graph.setX_AxisLabel("Time Pred RI (tpInterval2List) (years)");
		graph.setY_AxisLabel("Observed RI (years)");
		graph.setAllLineTypes(null, PlotSymbol.CROSS);
		graph.setYLog(true);
		graph.setXLog(true);
		if(saveStuff) {
			try {
				graph0.saveAsPDF(dirNameForSavingFiles+"/obsVersusAveElementRIs.pdf");
				graph.saveAsPDF(dirNameForSavingFiles+"/obsVersusTimePred2_RIs.pdf");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		
		// Plot average norm RI along rupture
		for(int i=0;i<aveNormRI_AlongRup.size();i++) {
			aveNormRI_AlongRup.set(i, aveNormRI_AlongRup.getY(i)/numRIsAlongHist.getY(i));
		}
		ArrayList<DiscretizedFunc> funcList = new ArrayList<DiscretizedFunc>();
		funcList.add(aveNormRI_AlongRup);
		ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM, 2f, Color.BLACK));
		GraphWindow graph9 = new GraphWindow(funcList, "Ave Normalized RI Along Rupture", curveCharacteristics); 
		graph9.setX_AxisLabel("Normalized Distance From End of Rupture");
		graph9.setY_AxisLabel("Normalized Ave RI");
		if(saveStuff) {
			try {
				graph9.saveAsPDF(dirNameForSavingFiles+"/NormalizedSlipAlongRup"+".pdf");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		// write full distributions values to file
		FileWriter fw;
		try {
			fw = new FileWriter("riDistsAlongStrikeXYZ.txt");
			fw.write("normDistAlong\tnormRI\tfraction\n");
			ArrayList<DiscretizedFunc> normRI_AlongRupFuncList = new ArrayList<DiscretizedFunc>();
			for(int i=0;i<aveNormRI_AlongRup.size();i++) {
				double distAlong = aveNormRI_AlongRup.getX(i);
				HistogramFunction hist = riDistsAlongAlongRup[i];
				hist.normalizeBySumOfY_Vals();
				hist.setName("Dist Along "+(float)distAlong);
				normRI_AlongRupFuncList.add(hist);
				for(int j=0;j<hist.size();j++)
					fw.write((float)distAlong+"\t"+(float)hist.getX(j)+"\t"+(float)hist.getY(j)+"\n");
//				}
			}
//			GraphWindow graph10 = new GraphWindow(normRI_AlongRupFuncList, "Normalized RI Along Rupture"); 
//			graph10.setX_AxisLabel("Normalized RI");
//			graph10.setY_AxisLabel("Franction");
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}


		

		// print and plot the test element correlations
		if(testElementID != null) {
			tempInfoString +="\nCorrelations (and chance it's random) between Predicted Intervals That Involve Element ID="+testElementID+":\n";
			result = getCorrelationAndP_Value(tpInterval2ListForTestElement, spInterval2ListForTestElement);
			tempInfoString +="\t"+(float)result[0]+"\t("+result[1]+") for tpInterval2 vs spInterval2List (num pts ="+tpInterval2ListForTestElement.size()+")\n";
			ArrayList<DefaultXY_DataSet> obs_tp1_funcsForTestElement = new ArrayList<DefaultXY_DataSet>();
			DefaultXY_DataSet xy_data = new DefaultXY_DataSet(tpInterval2ListForTestElement,spInterval2ListForTestElement);
			obs_tp1_funcsForTestElement.add(xy_data);
			GraphWindow graph3 = new GraphWindow(obs_tp1_funcsForTestElement, "Slip-Pred vs Time-Pred RIs at Element ID="+testElementID);   
			graph3.setX_AxisLabel("Time-Pred RI (years)");
			graph3.setY_AxisLabel("Slip-Pred RI (years)");
			graph3.setAllLineTypes(null, PlotSymbol.CROSS);
			graph3.setYLog(true);
			graph3.setXLog(true);	
			if(saveStuff) {
				try {
					graph3.saveAsPDF(dirNameForSavingFiles+"/slipVersusTimePred2_RI_AtElemID"+testElementID+".pdf");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}


		// Make the norm TP RI histogram and scatter plot for each section
		if(plotSectionResults) {
			String subDir = "sectionPlots";
			File file1 = new File(dirNameForSavingFiles,subDir);
			file1.mkdirs();

			for(int s=0;s<sectionNamesList.size();s++) {
//				System.out.println("Working on "+s+"\t"+namesOfSections.get(s));
				ArrayList<Double> sectNormObsIntervalList = new ArrayList<Double>();
				for(int i=0; i<norm_aveElementIntervalList.size();i++) {
					if(nucleationSectionList.get(i) == (s+1)) { // does section ID correspond to section index
						sectNormObsIntervalList.add(norm_aveElementIntervalList.get(i));
					}
				}
				if(sectNormObsIntervalList.size()>2){	// only do it if there are more than 2  data points
					// Plot RI PDF
					String plotTitle10 = "Normalized Obs Interval (norm_aveElementIntervalList) for "+sectionNamesList.get(s);
					HeadlessGraphPanel plot10 = getNormRI_DistributionGraphPanel(sectNormObsIntervalList, plotTitle10);

					// plot obs vs predicted scatter plot
					String plotTitle11 = "Norm Obs RI vs Norm Last Slip for "+sectionNamesList.get(s);
					HeadlessGraphPanel plot11 = new HeadlessGraphPanel();
					ArrayList<DefaultXY_DataSet> tempList = new ArrayList<DefaultXY_DataSet>();
					tempList.add(normObs_vs_normLastSlip_funcsMap.get(s));
					ArrayList<PlotCurveCharacterstics> curveCharacteristics2 = new ArrayList<PlotCurveCharacterstics>();
					curveCharacteristics2.add(new PlotCurveCharacterstics(PlotSymbol.CROSS, 2f, Color.RED));
					//						plot11.setUserBounds(10, 10000, 10, 10000);
					plot11.setXLog(true);
					plot11.setYLog(true);
					plot11.drawGraphPanel("Norm Last Slip", "Norm Observed RI", tempList, curveCharacteristics2, plotTitle11);
					plot11.getChartPanel().setSize(1000, 800);
					if(saveStuff) {
						String fileName10 = dirNameForSavingFiles+"/"+subDir+"/normObsIntervalDist_forSect"+s+".pdf";
						String fileName11 = dirNameForSavingFiles+"/"+subDir+"/normObsVsLastSlip_forSect"+s+".pdf";
						try {
							plot10.saveAsPDF(fileName10);
							plot11.saveAsPDF(fileName11);
							//								plot7.saveAsPNG(fileName7);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}			
				}
			}
		}


		tempInfoString +="\n"+numBad+" events were bad (e.g., no previous event time because it was first)\n";

		tempInfoString +="minElementArea="+(float)minElementArea+"\tmaxElementArea"+(float)maxElementArea+"\n";

		try {
			if(saveStuff) {
				FileWriter fw_timePred = new FileWriter(dirNameForSavingFiles+"/TimePredTestData.txt");
				fw_timePred.write(linesFor_fw_timePred);
				fw_timePred.close();
			}
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		System.out.println(tempInfoString);
		return tempInfoString;

	}

	
	/**
	 * This computes the correlation coefficient and the p-value between the two lists.  
	 * The p-value represents the two-tailed significance of the result (and it depends on the 
	 * number of points).  This represents the probability that a truly random process
	 * would produce a correlation greater than the value or less than the negative value.  
	 * In other words, if you reject the null hypothesis that there is no correlation, then
	 * there is the p-value chance that you are wrong.  The one sided values are exactly half 
	 * the two-sided values.  I verified the p-values against an on-line calculator.
	 * @param list1
	 * @param list2
	 * @return double[2], where the first element is the correlation and the second is the p-value
	 */
	private double[] getCorrelationAndP_Value(ArrayList<Double> list1, ArrayList<Double> list2) {
		double[][] vals = new double[list1.size()][2];
		for(int i=0;i<list1.size();i++) {
			vals[i][0] = list1.get(i);
			vals[i][1] = list2.get(i);
		}
		PearsonsCorrelation corrCalc = new PearsonsCorrelation(vals);
		double[] result = new double[2];
		RealMatrix matrix;
		matrix = corrCalc.getCorrelationMatrix();
		result[0] = matrix.getEntry(0, 1);
		matrix = corrCalc.getCorrelationPValues();
		result[1] = matrix.getEntry(0, 1);
		return result;
	}

	
	public void checkThatAllEventRecordsHaveSlips() {
		System.out.println("checkThatAllEventRecordsHaveSlips");
		for(SimulatorEvent event:eventList) {
			if(event.hasElementSlipsAndIDs()) {
				for(int i=0; i<event.size();i++) {
					EventRecord er = event.get(i);
					if(!er.hasElementSlipsAndIDs()) {
						System.out.println("Event ID "+event.getID()+" has missing slips and IDs on record index "+i);
					}
				}
			}
		}

	}
	
	
	/**
	 * This compares all the computed magnitudes to those given on the input files 
	 * and writes out the maximum absolute difference.
	 */
	public String checkEventMagnitudes(double magThresh) {

		double maxMagDiff = 0;
		int maxDiffIndex = -1;

		// loop over all events
		int i=-1;
		for(SimulatorEvent event:eventList) {
			i++;
			if(event.hasElementSlipsAndIDs() && isEventSupraSeismogenic(event, magThresh)) {

				double eventMag = event.getMagnitude();
				double moment =0;
				double[] slips = event.getAllElementSlips();
				int[] elemIDs = event.getAllElementIDs();
				int numElements = slips.length;
				for(int e=0;e<numElements;e++) {
					int index = elemIDs[e]-1;
					double area = rectElementsList.get(index).getArea();	// given in meters-sq
					double slip = slips[e]; // this is in meters
					moment += FaultMomentCalc.getMoment(area, slip);	// convert area to meters squared
				}
				double computedMag = MagUtils.momentToMag(moment);
				double diff = Math.abs(eventMag-computedMag);
				if(diff> maxMagDiff) {
					maxMagDiff = diff;
					maxDiffIndex = i;
				}
			}
		}
		String info = "maximum abs(eventMag-computedMag) ="+maxMagDiff+"; for eventList index "+
						maxDiffIndex+" (ID="+eventList.get(maxDiffIndex).getID()+")\n";
		System.out.println(info);
		System.out.println(eventList.get(maxDiffIndex).toString());
		return info;
	}
	
	
	public void writeEventsThatInvolveMultSections() {
		System.out.println("Events that involve more than one section:");
		System.out.println("\t\tEvID\t# Sect\tMag\tSections involved...");
		int num =0;
		for(SimulatorEvent event:eventList) {
			if(event.size()>1) {
				num += 1;
				double mag = Math.round(event.getMagnitude()*100.0)/100.0;
				System.out.print("\t"+num+"\t"+event.getID()+"\t"+event.size()+"\t"+mag);
				for(EventRecord rec:event)
					System.out.print("\t"+this.sectionNamesList.get(sectionID_indexMap.get(rec.getSectionID()))+"_"+rec.getSectionID());
				System.out.print("\n");
			}
		}
	}
	
	/**
	 * 
	 * @return simulation duration in seconds
	 */
	public double getSimulationDuration() {
		return SimulatorUtils.getSimulationDuration(eventList);
	}
	
	/**
	 * 
	 * @return simulation duration in years
	 */
	public double getSimulationDurationYears() {
		return SimulatorUtils.getSimulationDurationYears(eventList);
	}
	
	/**
	 * This compares observed slip rate (from events) with those imposed.  This writes out
	 * the correlation coefficient to System, and optionally makes a plot, and optionally saves
	 * the plot and data to a file.
	 * @param fileNamePrefix - set as null to not write the data out.
	 */
	public void checkElementSlipRates(String fileNamePrefix, boolean makePlot) {

		FileWriter fw_slipRates;
		double[] obsAveSlipRate = new double[rectElementsList.size()];
		double[] imposedSlipRate = new double[rectElementsList.size()];
		int[] numEvents = new int[rectElementsList.size()];
		// loop over all events
		for(SimulatorEvent event:eventList) {
			if(event.hasElementSlipsAndIDs()) {
				double[] slips = event.getAllElementSlips();
				int[] elemIDs = event.getAllElementIDs();
				int numElements = slips.length;
				for(int e=0;e<numElements;e++) {
					int index = elemIDs[e]-1;
					obsAveSlipRate[index] += slips[e];
					numEvents[index] += 1;
// if(index==924) System.out.println(index+"\t"+event.getID()+"\t"+ slips[e]);
					//						if(eventNum ==3) System.out.println("Test: el_ID="+elementID_List.get(e).intValue()+"\tindex="+index+"\tslip="+slipList.get(e));
				}
			}
		}

		// finish obs and get imposed slip rates:
		double simDurr = getSimulationDurationYears();
		for(int i=0; i<obsAveSlipRate.length; i++) {
			obsAveSlipRate[i] /= simDurr;
			imposedSlipRate[i] = rectElementsList.get(i).getSlipRate();
		}

		PearsonsCorrelation corrCalc = new PearsonsCorrelation();
		double slipRateCorr = corrCalc.correlation(obsAveSlipRate, imposedSlipRate);
		System.out.println("Correlation between obs and imposed slip rate = "+(float)slipRateCorr);
		
		// make plot if desired
		if(makePlot) {
			DefaultXY_DataSet xy_data = new DefaultXY_DataSet(imposedSlipRate,obsAveSlipRate);
			xy_data.setName("Obs versus Imposed Slip Rate");
			xy_data.setInfo(" ");
			ArrayList<DefaultXY_DataSet> funcs = new ArrayList<DefaultXY_DataSet>();
			funcs.add(xy_data);
			GraphWindow graph = new GraphWindow(funcs, "Slip Rate Comparison");   
			graph.setX_AxisLabel("Imposed Slip Rate (m/s)");
			graph.setY_AxisLabel("Observed Slip Rate (m/s)");
			ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
			curveCharacteristics.add(new PlotCurveCharacterstics(PlotSymbol.FILLED_CIRCLE, 4f, Color.BLUE));
			graph.setPlotChars(curveCharacteristics);
			if(fileNamePrefix != null) {
				String plotFileName = fileNamePrefix +".pdf";
				try {
					graph.saveAsPDF(dirNameForSavingFiles+"/"+plotFileName);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		// write file if name is non null
		if(fileNamePrefix != null) {
			try {
				String dataFileName = dirNameForSavingFiles+"/"+fileNamePrefix+".txt";
				fw_slipRates = new FileWriter(dataFileName);
				fw_slipRates.write("obsSlipRate\timposedSlipRate\tdiff\tnumEvents\n");
				//				System.out.println(endTime+"\t"+startTime);
				for(int i=0; i<obsAveSlipRate.length; i++) {
					double diff = obsAveSlipRate[i]-imposedSlipRate[i];
					fw_slipRates.write(obsAveSlipRate[i]+"\t"+imposedSlipRate[i]+"\t"+diff+"\t"+numEvents[i]+"\n");
				}
				fw_slipRates.close();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
	}
	
	
	/**
	 * This plot the average normalized slip along ruptures
	 * @param magThresh - Double.NaN for supra seismogenic
	 * This returns false if the plot can't be made (e.g., ViscoSim has  
	 * some problem with event.getNormDistAlongRupForElements())
	 * @param savePlot
	 */
	public boolean plotAveNormSlipAlongRupture(Double magThresh, boolean savePlot) {
		double startX = 0.0125;
		double deltaX = 0.025;
		int numX = 40;
		HistogramFunction normSlipAlongHist = new HistogramFunction(startX, numX, deltaX);
		for(EQSIM_Event event:eventList) {
			if(isEventSupraSeismogenic(event, magThresh) && event.hasElementSlipsAndIDsOnAllRecords()) {	// the latter will skip a few in the Ward file
				double[] slipArray = event.getAllElementSlips();
				double[] normDistAlong = event.getNormDistAlongRupForElements();
				
				HistogramFunction normSlipHistForRup = new HistogramFunction(startX, numX, deltaX);
				HistogramFunction totSlipHist = new HistogramFunction(startX, numX, deltaX);
				HistogramFunction numSlip = new HistogramFunction(startX, numX, deltaX);
				for(int i=0;i<slipArray.length;i++) {
					int index = totSlipHist.getXIndex(normDistAlong[i]);
					if(index == -1)
						return false;
					totSlipHist.add(index, slipArray[i]);
					numSlip.add(index, 1);
				}
				// compute ave slip in each bin
				for(int i=0;i<numX;i++) {
					if(numSlip.getY(i)>0)
						normSlipHistForRup.set(i,totSlipHist.getY(i)/numSlip.getY(i));
				}
				normSlipHistForRup.normalizeBySumOfY_Vals();
				
				// now add to total hist
				for(int i=0;i<numX;i++)
					normSlipAlongHist.add(i, normSlipHistForRup.getY(i));
			}
		}
		normSlipAlongHist.normalizeBySumOfY_Vals();
		
		// make it symmetric
		HistogramFunction symNormSlipAlongHist = new HistogramFunction(startX, numX, deltaX);
		for(int i=0;i<Math.floor((float)numX/2.0);i++) {
			int i2 = numX-1-i;
			double val = (normSlipAlongHist.getY(i)+normSlipAlongHist.getY(i2))/2.0;
			symNormSlipAlongHist.add(i,val);
			symNormSlipAlongHist.add(i2,val);
		}

		HistogramFunction sqrtSineHist = new HistogramFunction(startX, numX, deltaX);
		for(int i=0;i<numX;i++) {
			double xVal = sqrtSineHist.getX(i)*Math.PI;
			sqrtSineHist.set(i,Math.sqrt(Math.sin(xVal)));
		}
		sqrtSineHist.normalizeBySumOfY_Vals();
		
		ArrayList<DiscretizedFunc> funcList = new ArrayList<DiscretizedFunc>();
		funcList.add(symNormSlipAlongHist);
		funcList.add(sqrtSineHist);
		GraphWindow graph = new GraphWindow(funcList, "Ave Normalized Slip Along Rupture"); 
		graph.setX_AxisLabel("Normalized Distance Along Rupture");
		graph.setY_AxisLabel("Normalized Slip");
		ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM, 2f, Color.BLACK));
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 2f, Color.RED));
		graph.setPlotChars(curveCharacteristics);
		if(savePlot) {
			try {
				graph.saveAsPDF(dirNameForSavingFiles+"/NormalizedSlipAlongRup"+".pdf");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		return true;
//		System.out.println(symNormSlipAlongHist);

	}
	
	
	/**
	 * This plots slip versus length, mag versus area, and mag versus length.
	 */
	public void plotScalingRelationships(boolean savePlotsToFile) {
		double[] slip = new double[eventList.size()];
		double[] mag = new double[eventList.size()];
		double[] area = new double[eventList.size()];
		double[] length = new double[eventList.size()];
		
		int index = -1;
		for(EQSIM_Event event:eventList) {
			index +=1;
			slip[index]=event.getMeanSlip();
			mag[index]=event.getMagnitude();
			area[index]=event.getArea()/1e6; 		// convert to km-sq
			length[index]=event.getLength()/1000; 	// convert to km
		}
		/**/
		
		// SLIP VS LENGTH PLOT
		// Adding Bruce's slip-length models for comparisons would require getting the physical parameters right 
		// (e.g., his default "w" is 15 km; which seems high compared to the simulator fault model)
		DefaultXY_DataSet s_vs_l_data = new DefaultXY_DataSet(length,slip);
		s_vs_l_data.setName("Mean Slip vs Length");
		s_vs_l_data.setInfo(" ");
		ArrayList s_vs_l_funcs = new ArrayList();
		s_vs_l_funcs.add(s_vs_l_data);
		GraphWindow s_vs_l_graph = new GraphWindow(s_vs_l_funcs, "Mean Slip vs Length");   
		s_vs_l_graph.setY_AxisLabel("Mean Slip (m)");
		s_vs_l_graph.setX_AxisLabel("Length (km)");
		ArrayList<PlotCurveCharacterstics> s_vs_l_curveChar = new ArrayList<PlotCurveCharacterstics>();
		s_vs_l_curveChar.add(new PlotCurveCharacterstics(PlotSymbol.CIRCLE, 3f, Color.BLUE));
		s_vs_l_graph.setPlotChars(s_vs_l_curveChar);
		
		// MAG VS AREA PLOT
		DefaultXY_DataSet m_vs_a_data = new DefaultXY_DataSet(area,mag);
		m_vs_a_data.setName("Mag-Area data from simulation");
		m_vs_a_data.setInfo(" ");
		ArrayList m_vs_a_funcs = new ArrayList();
		Ellsworth_B_WG02_MagAreaRel elB = new Ellsworth_B_WG02_MagAreaRel();
		HanksBakun2002_MagAreaRel hb = new HanksBakun2002_MagAreaRel();
	//	WC1994_MagAreaRelationship wc = new WC1994_MagAreaRelationship();
	//	wc.setRake(0);
		Shaw_2007_MagAreaRel sh = new Shaw_2007_MagAreaRel();
		m_vs_a_funcs.add(m_vs_a_data);	// do this first so it plots underneath
		m_vs_a_funcs.add(elB.getMagAreaFunction(1, 10000, 101));
		m_vs_a_funcs.add(hb.getMagAreaFunction(1, 10000, 101));
	//	m_vs_a_funcs.add(wc.getMagAreaFunction(1, 10000, 101));
		m_vs_a_funcs.add(sh.getMagAreaFunction(1, 10000, 101));
		GraphWindow m_vs_a_graph = new GraphWindow(m_vs_a_funcs, "Mag vs Area");   
		m_vs_a_graph.setY_AxisLabel("Magnitude (Mw)");
		m_vs_a_graph.setX_AxisLabel("Area (km-sq)");
		ArrayList<PlotCurveCharacterstics> m_vs_a_curveChar = new ArrayList<PlotCurveCharacterstics>();
		m_vs_a_curveChar.add(new PlotCurveCharacterstics(PlotSymbol.CIRCLE, 3f, Color.BLACK));
		m_vs_a_curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.RED));
		m_vs_a_curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.GREEN));
		m_vs_a_curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.BLUE));
//		m_vs_a_curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.MAGENTA));
		m_vs_a_graph.setPlotChars(m_vs_a_curveChar);
		m_vs_a_graph.setXLog(true);
		m_vs_a_graph.setY_AxisRange(4.5, 8.5);
	/**/
		// MAG VS LENGTH PLOT
		DefaultXY_DataSet m_vs_l_data = new DefaultXY_DataSet(length,mag);
		m_vs_l_data.setName("Mag vs Length");
		m_vs_l_data.setInfo(" ");
		ArrayList m_vs_l_funcs = new ArrayList();
		m_vs_l_funcs.add(m_vs_l_data);
		GraphWindow m_vs_l_graph = new GraphWindow(m_vs_l_funcs, "Mag vs Length");   
		m_vs_l_graph.setY_AxisLabel("Magnitude (Mw)");
		m_vs_l_graph.setX_AxisLabel("Length (km)");
		ArrayList<PlotCurveCharacterstics> m_vs_l_curveChar = new ArrayList<PlotCurveCharacterstics>();
		m_vs_l_curveChar.add(new PlotCurveCharacterstics(PlotSymbol.CIRCLE, 3f, Color.GREEN));
		m_vs_l_graph.setPlotChars(m_vs_l_curveChar);
//		m_vs_l_graph.setXLog(true);
		m_vs_l_graph.setY_AxisRange(4.5, 8.5);
		
		if(savePlotsToFile) {
			try {
				s_vs_l_graph.saveAsPDF(dirNameForSavingFiles+"/s_vs_l_graph.pdf");
				m_vs_a_graph.saveAsPDF(dirNameForSavingFiles+"/m_vs_a_graph.pdf");
				m_vs_l_graph.saveAsPDF(dirNameForSavingFiles+"/m_vs_l_graph.pdf");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}
	
	
	/**
	 * For the specified element ID, this computes the intervals between events that have 
	 * magnitude greater than the given threshold.
	 * @param elemID
	 * @param magThresh - set as Double.NaN to use sqrt(area)>aveFaultDDW instead
	 * @return
	 */
	public double[] getRecurIntervalsForElement(int elemID, double magThresh) {
		ArrayList<Double> eventTimes = new ArrayList<Double>();
		for(SimulatorEvent event:eventList)
			if(event.hasElementSlipsAndIDs())
				if(Ints.contains(event.getAllElementIDs(), elemID) && isEventSupraSeismogenic(event, magThresh))
					eventTimes.add(event.getTimeInYears());
		if (eventTimes.size()>0) {
			double[] intervals = new double[eventTimes.size()-1];
			for(int i=1;i<eventTimes.size();i++)
				intervals[i-1] = (eventTimes.get(i)-eventTimes.get(i-1));
			return intervals;
		}
		else return null;
	}
	
	
	public void writeRI_COV_forAllSurfaceElements(double magThresh, String fileName) {
		FileWriter fw;
		try {
			fw = new FileWriter(fileName);
			fw.write("elemID\tCOV\tfaultName\tNumRIs\n");
			// Loop over elements
			for(SimulatorElement elem:rectElementsList) {
				// check whether it's a surface element
				if(elem.getVertices()[0].getTraceFlag() != 0) {
//					System.out.println("trace vertex found");
					double[] recurInts = getRecurIntervalsForElement(elem.getID(), magThresh);
					if(recurInts==null) 
						continue;
					DescriptiveStatistics stats = new DescriptiveStatistics();
					for(double val:recurInts) {
						stats.addValue(Math.log10(val));
					}
					double cov = stats.getStandardDeviation()/stats.getMean();
					fw.write(elem.getID()+"\t"+cov+"\t"+elem.getName()+"\t"+recurInts.length+"\n");
				}
			}
			fw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	
	
	
	public void writeDAS_ForVertices() {
		FileWriter fw;
		try {
			fw = new FileWriter("tempDAS_forVertices.txt");
			for(Vertex vert:vertexList) {
				// check whether it's a surface element
//				if(vert.getTraceFlag() != 0) {
					String sectName = sectionNamesList.get(getSectionIndexForVertex(vert));
					fw.write(vert.getID()+"\t"+vert.getDAS()+"\t"+sectName+"\n");
//				}
			}
			fw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * This writes out the section index, ID (index+1), name, and fault 
	 * ID to a file called "simulatorSectionNamesEtc.txt"
	 */
	public void writeSectionNamesEtc() {
		FileWriter fw;
		try {
			fw = new FileWriter("simulatorSectionNamesEtc.txt");
			fw.write("index\tsectID\tsectName\tfaultID");
			for(int i=0;i<sectionNamesList.size();i++) {
					fw.write(i+"\t"+sectionIDs_List.get(i)+"\t"+
							sectionNamesList.get(i)+"\t"+faultIDs_ForSections.get(i)+"\n");
			}
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	
	/**
	 * This plots a histogram of normalized recurrence intervals for all surface elements
	 * (normalized by the average interval at each  element).  Note that the surface element
	 * doesn't need to slip, but rather any element below it.
	 * @param magThresh
	 */
	public void plotNormRecurIntsForAllSurfaceElements(double magThresh, boolean savePlot) {

		ArrayList<Double> vals = new ArrayList<Double>();
		// Loop over elements rather than vertices because using the zeroeth vertex of the 
		// element will avoid possibly overlapping vertices
		for(SimulatorElement elem:rectElementsList) {
			// check whether it's a surface element
			if(elem.getVertices()[0].getTraceFlag() != 0) {
				Vertex vert = elem.getVertices()[0];
				double das = vert.getDAS()*1000;
				int sectID = getSectionIndexForVertex(vert)+1;
				double[] recurInts = getRecurIntervalsForDAS_and_FaultID(das, sectID, magThresh);
//				double[] recurInts = getRecurIntervalsForElement(elem.getID(), magThresh);
				if(recurInts != null) {
					double mean=0;
					for(int i=0;i<recurInts.length; i++) 
						mean += recurInts[i]/recurInts.length;
					for(int i=0;i<recurInts.length; i++) {
						vals.add(recurInts[i]/mean);
					}					
				}
			}
		}
//		GraphWindow graph = plotNormRI_Distribution(vals, "Normalized RI for All Surface Elements", Double.NaN);
		GraphWindow graph = ProbModelsPlottingUtils.plotNormRI_DistributionWithFits(ProbModelsPlottingUtils.getNormRI_DistributionWithFits(vals, Double.NaN), "Normalized RI for All Surface Elements");

		if(savePlot)
			try {
				graph.saveAsPDF(dirNameForSavingFiles+"/NormRecurIntsForAllSurfaceElements.pdf");
				graph.saveAsTXT(dirNameForSavingFiles+"/NormRecurIntsForAllSurfaceElements.txt");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	}



	
	/**
	 * This quits and returns false if the loc is not within 5 km of a vertex.
	 * @param loc
	 * @param magThresh
	 * @param savePlot
	 * @param locName
	 * @param infoString
	 */
	public boolean plotRecurIntervalsForNearestLoc(Location loc, double magThresh, boolean savePlot, String locName, String infoString) {
		// find the nearest vertex
		Vertex vert = getClosestVertex(loc);
		double dist = vert.getLinearDistance(loc);
		
		// quit and return null if not near element
		if(dist>5.0) {
			System.out.println("No vertex found near the site "+locName);
			return false;
		}
		
		double das = vert.getDAS()*1000;
		int sectIndex = getSectionIndexForVertex(vert);
		int sectID = sectionIDs_List.get(sectIndex);
		
//System.out.println(vert.getID()+"\t"+das+"\t"+(float)dist+"\t"+locName+"\t"+sectID+"\t"+namesOfSections.get(sectID-1));
		
		double[] intervals = getRecurIntervalsForDAS_and_FaultID(das, sectID, magThresh);
		
		if(intervals == null) {
			System.out.println("Not more than two events at "+locName);
			return false;
		}
		
		if(infoString== null)
			infoString = new String();
		infoString += "Closest Vertex is ID="+vert.getID()+" on "+sectionNamesList.get(sectIndex)+" ("+(float)dist+" km away)\n";
		plotRecurIntervalsForElement(intervals, savePlot, locName, infoString);
		return true;
	}
		
	public void plotRecurIntervalsForElement(double[] intervals, boolean savePlot, String locName, String infoString) {
				
		double maxInterval=0;
		double meanInterval=0;
		for(int i=0;i<intervals.length;i++) {
			if(intervals[i]>maxInterval) maxInterval = intervals[i];
			meanInterval += intervals[i];
		}
		meanInterval /= intervals.length;
		
		if(infoString == null)
			infoString = new String();
		infoString += "Num RIs = "+intervals.length+"\n";
		
		double binWidth = Math.round(meanInterval/10.0);
		int numBins = (int)Math.ceil(maxInterval/binWidth)+1;
		HistogramFunction riHist = new HistogramFunction(binWidth/2.0, numBins, binWidth);	
		for(int i=0; i<intervals.length;i++) {
			int testIndex = riHist.getXIndex(intervals[i]);
			if(testIndex == -1) {
				System.out.println(i+"\t"+intervals[i]+"\t"+binWidth+"\t"+numBins+"\t"+meanInterval+"\t"+maxInterval+"\t"+intervals.length);
			}
			
			riHist.add(intervals[i], 1.0/(binWidth*intervals.length));	// binWidth this makes it a density function
		}

		// now compute stdDevOfMean & 95% conf
		double stdDevOfMean=0;
		for(int i=0; i<intervals.length;i++) {
			stdDevOfMean += (intervals[i]-meanInterval)*(intervals[i]-meanInterval);
		}
		stdDevOfMean = Math.sqrt(stdDevOfMean/(intervals.length-1)); // this is the standard deviation
		stdDevOfMean /= Math.sqrt(intervals.length); // this is the standard deviation of mean
		double upper95 = meanInterval+1.96*stdDevOfMean;
		double lower95 = meanInterval-1.96*stdDevOfMean;
		
		infoString += "meanRI="+Math.round(meanInterval)+"\tlower95="+Math.round(lower95)+"\tupper95="+Math.round(upper95)+"\n";
		infoString += "mean from Histogram="+riHist.computeMean()+"\tCOV from Histogram="+riHist.computeCOV()+"\n";
		riHist.normalizeBySumOfY_Vals();
		infoString += riHist.toString();

		riHist.setName(locName+" RI histogram");
		riHist.setInfo(infoString);
		
		ArrayList<DiscretizedFunc> funcList = new ArrayList<DiscretizedFunc>();
		funcList.add(riHist);
		ArrayList<PlotCurveCharacterstics> curveCharacteristics = new ArrayList<PlotCurveCharacterstics>();
		curveCharacteristics.add(new PlotCurveCharacterstics(PlotLineType.HISTOGRAM, 2f, Color.BLACK));
		GraphWindow graph = new GraphWindow(funcList, "Recurence Intervals for "+locName, curveCharacteristics); 
		graph.setX_AxisLabel("RI (yrs)");
		graph.setY_AxisLabel("Density");
		graph.setX_AxisRange(0, 5*meanInterval);
		graph.setY_AxisRange(0, 2.3/meanInterval);
		graph.setAxisLabelFontSize(22);
		graph.setTickLabelFontSize(20);
		graph.setPlotLabelFontSize(22);
		if(savePlot)
			try {
				graph.saveAsPDF(dirNameForSavingFiles+"/RI_HistogramFor_"+locName+".pdf");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	}
	
	
	/**
	 * This returns the vertex that is closest to the given location
	 * (although this might not be very close)
	 * @param loc
	 * @return
	 */
	public Vertex getClosestVertex(Location loc) {
		double minDist= Double.MAX_VALUE;
		int vertexIndex=-1;
		//Find nearest Element
		for(int i=0; i<vertexList.size(); i++) {
			double dist = LocationUtils.linearDistance(loc, vertexList.get(i));
			if(dist<minDist){
				minDist=dist;
				vertexIndex= i;
			}
		}
		return vertexList.get(vertexIndex);
	}
	
	
	/**
	 * This returns the section index (not ID) for the given vertex, assuming
	 * there is only one section for each vertex (exception is thrown if not)
	 * (not efficient)
	 * @param vertex
	 * @return
	 */
	public int getSectionIndexForVertex(Vertex vertex) {

		int sectIndex = -1;
		boolean alreadyGotOne = false;
		// find the section index for the closest vertex (not efficient)
		for(int i=0; i<vertexListForSections.size(); i++)
			if(vertexListForSections.get(i).contains(vertex))
				if(alreadyGotOne == true)
					throw new RuntimeException("more than one section associated to vertex");
				else {
					sectIndex = i;
					alreadyGotOne=true;
				}
		return sectIndex;
	}
	
	
	/**
	 * This get the RI for all events that pass below the given DAS & sectID
	 * (the way Keith Richards Dinger does it).
	 * 
	 * This returns null if there are not more than two event times
	 * 
	 * @param das - in meters!
	 * @param sectID - id of fault section (not index!)
	 * @param magThresh
	 * @return
	 */
	public double[] getRecurIntervalsForDAS_and_FaultID(double das, int sectID, double magThresh) {
		ArrayList<Double> eventTimes = new ArrayList<Double>();
		for(EQSIM_Event event:eventList) {
			if(isEventSupraSeismogenic(event, magThresh) && event.doesEventIncludeSectionAndDAS(sectID,das)) {
						eventTimes.add(event.getTimeInYears());
			}
		}
		if(eventTimes.size() <=1)
			return null;
		double[] intervals = new double[eventTimes.size()-1];
		double maxInterval=0;
		for(int i=1;i<eventTimes.size();i++) {
			intervals[i-1] = (eventTimes.get(i)-eventTimes.get(i-1));
			if(intervals[i-1]>maxInterval) maxInterval = intervals[i-1];
		}
		return intervals;
	}

	
	
	/**
	 * This is no longer used or needed?
	 * @param dirNameForSavingFiles
	 * @param magThresh
	 */
	public void oldTests(String dirNameForSavingFiles, double magThresh) {
		ArrayList<String> infoStrings = new ArrayList<String>();
		this.dirNameForSavingFiles = dirNameForSavingFiles;
		File file1 = new File(dirNameForSavingFiles);
		file1.mkdirs();

		infoStrings.add("Simulation Duration is "+(float)this.getSimulationDurationYears()+" years");
		
		// plot & save scaling relationships
//		plotScalingRelationships(true);
		
//		plotNormRecurIntsForAllSurfaceElements(magThresh, true);
		
		/*
		// need to loop over all interesting sites, and to add observed dists
		ArrayList<Location> locList = ucerf2_dataFetcher.getParsonsSiteLocs();
		ArrayList<String> namesList = ucerf2_dataFetcher.getParsonsSiteNames();
		for(int i=0;i<locList.size();i++)
			getRecurIntervalsForNearestLoc(locList.get(i), 6.5, true, true,namesList.get(i));
*/
		
		// this is a location that has a very non-BPT looking PDF of recurrence times.
//		Location loc = rectElementsList.get(497-1).getGriddedSurface().get(0, 1);
//		String info = plotRecurIntervalsForNearestLoc(loc, 6.5, true, false,loc.toString());
//		infoStrings.add(info);

//		computeTotalMagFreqDist(4.05,9.05,51,true,true);
		
//		computeMagFreqDistByFaultSection(4.05,9.05,51,true,true,true);
		
		randomizeEventTimes();
		String info2 = testTimePredictability(magThresh, true, null, true);
		infoStrings.add(info2);

		
//		System.out.println(infoStrings);

		try {
			FileWriter infoFileWriter = new FileWriter(this.dirNameForSavingFiles+"/INFO.txt");
			for(String string: infoStrings) 
				infoFileWriter.write(string+"\n");
			infoFileWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
	
	
	/**
	 * This looks at what ruptures do and do not rupture all the way down dip
	 * (as defined by the isEventSupraSeismogenic(event, Double.NaN) method)
	 */
	public String checkFullDDW_rupturing(boolean plotResult, boolean savePlot) {
		double minMagThatDoes=10;
		double maxMagThatDoesnt=0;
		int eventID_forMin=-1;
		int eventID_forMax=-1;
		String eventInfoForMin="";
		String eventInfoForMax="";
		ArbIncrementalMagFreqDist mfd_does = new ArbIncrementalMagFreqDist(4, 8.5, 46);
		mfd_does.setName("Rups Completely Down Dip");
		mfd_does.setTolerance(1.0);
		ArbIncrementalMagFreqDist mfd_doesNot = new ArbIncrementalMagFreqDist(4, 8.5, 46);
		mfd_doesNot.setName("Does Not Rup Completely Down Dip");
		mfd_doesNot.setTolerance(1.0);
		for(SimulatorEvent event:eventList) {
			double mag = event.getMagnitude();
			if(mag<4) continue;
			if(isEventSupraSeismogenic(event, Double.NaN)) {
				if(mag<minMagThatDoes) {
					minMagThatDoes=mag;
					eventID_forMin=event.getID();
					double area = Math.round(event.getArea()*1e-6);
					double length = Math.round(event.getLength()*1e-3);
					double ddw = Math.round(1e-3*event.getArea()/event.getLength());
					eventInfoForMin = "area="+area+"; length="+length+"; area/length="+ddw+"; time(yrs)="+event.getTimeInYears();
//					eventInfoForMin += "\ntoString():\n"+event.toString();
				}
				mfd_does.add(mag, 1.0);
			}
			else {
				if(mag>maxMagThatDoesnt) {
					maxMagThatDoesnt=mag;
					eventID_forMax=event.getID();
					double area = Math.round(event.getArea()*1e-6);
					double length = Math.round(event.getLength()*1e-3);
					double ddw = Math.round(1e-3*event.getArea()/event.getLength());
					eventInfoForMax = "area="+area+"; length="+length+"; area/length="+ddw+"; time(yrs)="+event.getTimeInYears();
//					eventInfoForMax += "\ntoString():\n"+event.toString();
				}
				mfd_doesNot.add(mag, 1.0);
			}
		}
		String info = "min full seismogenic mag = "+minMagThatDoes+"\teventID="+eventID_forMin+"\t"+eventInfoForMin+"\n";
		info += "max non full seismogenic mag = "+maxMagThatDoesnt+"\teventID="+eventID_forMax+"\t"+eventInfoForMax+"\n";
		System.out.println(info);
		
		if(plotResult) {
			ArrayList<ArbIncrementalMagFreqDist> funcs = new ArrayList<ArbIncrementalMagFreqDist>();
			funcs.add(mfd_does);
			funcs.add(mfd_doesNot);
			ArrayList<PlotCurveCharacterstics> curveChar = new ArrayList<PlotCurveCharacterstics>();
			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.BLACK));
			curveChar.add(new PlotCurveCharacterstics(PlotLineType.SOLID, 3f, Color.GRAY));
			GraphWindow graph = new GraphWindow(funcs, "Full Rup Mags (and not)",curveChar); 
			graph.setX_AxisLabel("Mag");
			graph.setY_AxisLabel("Number of Observations");
			graph.setY_AxisRange(0.1, Math.ceil(mfd_doesNot.getMaxY()));
			graph.setYLog(true);
			if(savePlot) {
				try {
					graph.saveAsPDF(dirNameForSavingFiles+"/fullDDW_MFDs.pdf");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		return info;
	}
	
	/**
	 * This sets the directory name for files that are saved
	 * @param dirNameForSavingFiles
	 */
	public void setDirNameForSavingFiles(String dirNameForSavingFiles) {
		this.dirNameForSavingFiles=dirNameForSavingFiles;
		File file1 = new File(dirNameForSavingFiles);
		file1.mkdirs();
	}
	
	
	
	public void mkFigsForUCERF3_ProjPlanRepot(String dirNameForSavingFiles, double magThresh) {
		ArrayList<String> infoStrings = new ArrayList<String>();
		this.dirNameForSavingFiles = dirNameForSavingFiles;
		File file1 = new File(dirNameForSavingFiles);
		file1.mkdirs();

		infoStrings.add("Simulation Duration is "+(float)this.getSimulationDurationYears()+" years");
		
		// this is a location that has a very non-BPT looking PDF of recurrence times for "eqs.NCA_RSQSim.barall.txt" file.
		Location loc = ((RectangularElement)rectElementsList.get(497-1)).getSurface().get(0, 1);
		plotRecurIntervalsForNearestLoc(loc, 6.5, true,"RI_distAt_NSAF_ElementID497", "");
		
		Integer testElementID = Integer.valueOf(661); // not sure how this was chosen
//testElementID = null; 
		
		String info2 = testTimePredictability(magThresh, true, testElementID, true);
		infoStrings.add(info2);

//		System.out.println(infoStrings);

		try {
			FileWriter infoFileWriter = new FileWriter(this.dirNameForSavingFiles+"/INFO.txt");
			for(String string: infoStrings) 
				infoFileWriter.write(string+"\n");
			infoFileWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
	
	/**
	 * This tells whether the event utilizes any section with the given faultID
	 * @param faultID
	 * @return
	 */
	public boolean doesEventUtilizedFault(SimulatorEvent event, int faultID) {
		boolean answer = false;
		for(EventRecord eventRecord: event) {
			if(faultIDs_ForSections.get(sectionID_indexMap.get(eventRecord.getSectionID())) == faultID) {
				answer = true;
				break;
			}
		}
		return answer;	
	}

	public double getAreaForSection(int sectID) {
		return areaForSections.get(sectionID_indexMap.get(sectID));
	}

	public double getLengthForSection(int sectID) {
		return lengthForSections.get(sectionID_indexMap.get(sectID));
	}
	
	/**
	 * @param args
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public static void main(String[] args) throws FileNotFoundException, IOException {
		
		General_EQSIM_Tools t = new General_EQSIM_Tools(new File("/home/kevin/Simulators/ALLCAL2_1-7-11_Geometry.dat"));
		
		for (int i=0; i<10; i++)
			System.out.println("Area "+i+": "+t.areaForSections.get(i));
		System.exit(0);
		
		long startTime=System.currentTimeMillis();
		System.out.println("Starting");
/*		
		// RSQSim Runs:
		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCA_Ward_Geometry.dat.txt";
		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath);
//		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/eqs.NCA_RSQSim.barall.txt");
		test.read_EQSIMv04_EventsFile("/Users/field/workspace/OpenSHA/src/org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/eqs.NCA_RSQSim.barall.txt");
		test.mkFigsForUCERF3_ProjPlanRepot("RSQSim_Run",  6.5);
		
//		test.randomizeEventTimes();
//		test.mkFigsForUCERF3_ProjPlanRepot("RSQSim_Run_Randomized",  6.5);
*/
/*	*/	
		/*
		// VC Runs:
		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCA_Ward_Geometry.dat.txt";
		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath);
		File simEventFile = new File("/Users/field/workspace/OpenSHA/src/org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith","VC_norcal.d.txt");
		test.read_EQSIMv04_EventsFile(simEventFile);
//		test.mkFigsForUCERF3_ProjPlanRepot("VC_Run",  6.5);
		test.checkElementSlipRates("VC_Run", true);
		*/

		/*
		// Ward Runs:
//		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCAL4_Ward_Geometry.dat.txt";
//		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath);
//		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCAL4-WARD-30k.dat");
		String fullPath = "org/opensha/sha/simulators/eqsim_v04/WardsInputFile/test.txt";  // I had to rename the file "NCAL(9/1/10)-elements.dat.txt" to test.txt to get this to work
		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath, 1);
		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCAL_Ward.out.txt");

		test.mkFigsForUCERF3_ProjPlanRepot("Ward_Run",  6.5);
	*/
		
		// create UCERF3 deformation model files
		
		FaultModels fm = FaultModels.FM3_1;
		DeformationModels dm = DeformationModels.ZENGBB;
		double defaultAseisVal = 0.1;
		File scratchDir = UCERF3_DataUtils.DEFAULT_SCRATCH_DATA_DIR;
		File outputDir = new File("/tmp/ucerf3_dm_eqsim_files");
		if (!outputDir.exists())
			outputDir.mkdir();
		boolean aseisReducesArea = false; // TODO
		double maxDiscretization = 4.0; // TODO
		DateFormat df = new SimpleDateFormat("MMM d, yyyy");
		DeformationModelFetcher dmFetch = new DeformationModelFetcher(fm, dm, scratchDir, defaultAseisVal);
		General_EQSIM_Tools tools = new General_EQSIM_Tools(dmFetch.getSubSectionList(), aseisReducesArea, maxDiscretization);
		File outputFile = new File(outputDir, fm.encodeChoiceString()+"_"+dm.encodeChoiceString()+"_EQSIM.txt");
//		tools.writeTo_EQSIM_V04_GeometryFile(outputFile.getAbsolutePath(), null,
//				fm.name()+", "+dm.name()+" output", "Kevin Milner", df.format(new Date()));
		// TODO
		
		// now try opening it
		new General_EQSIM_Tools(outputFile);
		
		
		int runtime = (int)(System.currentTimeMillis()-startTime)/1000;
		System.out.println("This Run took "+runtime+" seconds");

		
		//OLD JUNK BELOW
		
		
		
		
		/*
		// this is for analysis of the Ward Results:
		String fullPath = "org/opensha/sha/simulators/eqsim_v04/WardsInputFile/test.txt";
		// I had to rename the file "NCAL(9/1/10)-elements.dat.txt" to test.txt to get this to work
		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath, 1);
		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCAL_Ward.out.txt");
		test.checkEventMagnitudes();
		test.checkElementSlipRates("testSlipRateFileForWard");
		test.testTimePredictability(6.5, "testTimePredFileForWard_M6pt5");
		 */
		
		// this is for analysis of the RQSim Results:
//		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCA_Ward_Geometry.dat.txt";
//		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath);
//		ArrayList<String> sectNames = test.getSectionsNameList();
//		System.out.println("Section Names (IDs)");
//		for(int s=0; s<sectNames.size();s++)	System.out.println("\t"+sectNames.get(s)+"("+(s+1)+")");
//		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/eqs.NCA_RSQSim.barall.txt");
//		test.checkEventMagnitudes();
//		test.checkElementSlipRates("testSlipRateFileForEQSim", true);
//		System.out.println("Simulation Duration is "+(float)test.getSimulationDurationInYears()+" years");
//		test.randomizeEventTimes();
//		test.plotYearlyEventRates();
//		test.test();
//		test.oldTests("NEDS_TEST", 6.5);
//		test.writeEventsThatInvolveMultSections();
		

		
		/*  This writes an EQSIM file from a UCERF2 deformation model
		General_EQSIM_Tools test = new General_EQSIM_Tools(82, false, 4.0);
//		test.getElementsList();
		String writePath = "testEQSIM_Output.txt";
		try {
			test.writeTo_EQSIM_V04_GeometryFile(writePath, null, "test UCERF2 output", "Ned Field", "Aug 3, 2010");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		*/

		
		/*
		// THE FOLLOWING TEST LOOKS GOOD FROM A VISUAL INSPECTION
		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCA_Ward_Geometry.dat.txt";
//		String fullPath = "org/opensha/sha/simulators/eqsim_v04/ALLCAL_Model_v04/ALLCAL_Ward_Geometry.dat";
		General_EQSIM_Tools test = new General_EQSIM_Tools(fullPath);
		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/NCAL_Ward.out.txt");
//		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/VC_norcal.d.txt");
//		test.read_EQSIMv04_EventsFile("org/opensha/sha/simulators/eqsim_v04/ExamplesFromKeith/eqs.NCA_RSQSim.barall.txt");


		System.out.println(test.getNumEventsWithElementSlipData()+" out of "+test.getEventsList().size()+" have slip on elements data");

		// find the mag cutoff for inclusion of element slip data
		double maxWithOut = 0;
		double minWith =10;
		for(EQSIM_Event event:test.getEventsList()) {
			if(event.hasElementSlipsAndIDs()) {
				if (event.getMagnitude()<minWith) minWith = event.getMagnitude();
			}
			else
				if(event.getMagnitude()>maxWithOut) maxWithOut = event.getMagnitude();
		}
		System.out.println("minWith="+minWith+";\tmaxWithOut="+maxWithOut);
		
		*/
						
	}
}
