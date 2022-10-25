package org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.opensha.commons.data.Named;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.IDPairing;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster.JumpStub;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.faultSurface.FaultSection;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.utils.DeformationModelFetcher;

/**
 * This abstract class defines cluster connections. The getClusters() method will add all
 * connections the first time that it is called.
 * 
 * @author kevin
 *
 */
public abstract class ClusterConnectionStrategy implements Named {

	private List<? extends FaultSection> subSections;
	private List<FaultSubsectionCluster> clusters;
	private SectionDistanceAzimuthCalculator distCalc;
	
	protected transient HashSet<IDPairing> connectedParents;
	protected transient boolean connectionsAdded = false;
	protected transient Multimap<FaultSection, Jump> jumpsFrom;
	
	public ClusterConnectionStrategy(List<? extends FaultSection> subSections, SectionDistanceAzimuthCalculator distCalc) {
		this(subSections, buildClusters(subSections), distCalc);
	}
	
	public ClusterConnectionStrategy(List<? extends FaultSection> subSections,
			List<FaultSubsectionCluster> clusters, SectionDistanceAzimuthCalculator distCalc) {
		this.subSections = ImmutableList.copyOf(subSections);
		this.clusters = ImmutableList.copyOf(clusters);
		this.distCalc = distCalc;
	}
	
	public SectionDistanceAzimuthCalculator getDistCalc() {
		return distCalc;
	}
	
	/**
	 * Builds clusters from the given sub-sections (without adding any connections)
	 * @param subSections
	 * @return
	 */
	public static List<FaultSubsectionCluster> buildClusters(List<? extends FaultSection> subSections) {
		List<FaultSubsectionCluster> clusters = new ArrayList<>();
		
		List<FaultSection> curClusterSects = null;
		int curParentID = -1;
		
		for (FaultSection subSect : subSections) {
			int parentID = subSect.getParentSectionId();
			Preconditions.checkState(parentID >= 0,
					"Subsections are required, but this section doesn't have a parent ID set: %s. %s",
					subSect.getSectionId(), subSect.getSectionName());
			if (parentID != curParentID) {
				if (curClusterSects != null)
					clusters.add(new FaultSubsectionCluster(curClusterSects));
				curParentID = parentID;
				curClusterSects = new ArrayList<>();
			}
			curClusterSects.add(subSect);
		}
		clusters.add(new FaultSubsectionCluster(curClusterSects));
		
		return clusters;
	}
	
	/**
	 * @return list of sub sections
	 */
	public synchronized List<? extends FaultSection> getSubSections() {
		return subSections;
	}
	
	/**
	 * @return list of full clusters, without guaranteeing that all connections have been added. For internal use while
	 * building connections.
	 */
	protected List<FaultSubsectionCluster> getRawClusters() {
		return clusters;
	}
	
	public synchronized void checkBuildThreaded(int numThreads) {
		if (!connectionsAdded) {
//			System.out.println("Building connections between "+clusters.size()+" clusters");
			buildConnections(numThreads);
//			System.out.println("Found "+count+" possible section connections");
		}
	}
	
	/**
	 * @return list of full clusters, after adding all connections
	 */
	public synchronized List<FaultSubsectionCluster> getClusters() {
		if (!connectionsAdded) {
//			System.out.println("Building connections between "+clusters.size()+" clusters");
			buildConnections(1);
//			System.out.println("Found "+count+" possible section connections");
		}
		return clusters;
	}

	/**
	 * Populates all possible connections between the given clusters (via the
	 * FaultSubsectionCluster.addConnection(Jump) method). This also populates the connectedParents and jumpsFrom maps.
	 * 
	 * @return the number of connections added
	 */
	private int buildConnections(int numThreads) {
		List<Jump> jumps = new ArrayList<>();
		
		List<ConnSearchCallable> calls = new ArrayList<>();
		for (int c1=0; c1<clusters.size(); c1++) {
			FaultSubsectionCluster cluster1 = clusters.get(c1);
			for (int c2=c1+1; c2<clusters.size(); c2++) {
				FaultSubsectionCluster cluster2 = clusters.get(c2);
				calls.add(new ConnSearchCallable(cluster1, cluster2));
			}
		}
		
		if (numThreads <= 1) {
			for (ConnSearchCallable call : calls) {
				List<Jump> newJumps;
				try {
					newJumps = call.call();
				} catch (Exception e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
				if (newJumps != null)
					jumps.addAll(newJumps);
			}
		} else {
			ExecutorService exec = Executors.newFixedThreadPool(numThreads);
			List<Future<List<Jump>>> futures = new ArrayList<>();
			
			for (ConnSearchCallable call : calls)
				futures.add(exec.submit(call));
			
			for (Future<List<Jump>> f : futures) {
				try {
					List<Jump> newJumps = f.get();
					if (newJumps != null)
						jumps.addAll(newJumps);
				} catch (Exception e) {
					exec.shutdown();
					throw ExceptionUtils.asRuntimeException(e);
				}
			}
			
			exec.shutdown();
		}
		
		connectedParents = new HashSet<>();
		jumpsFrom = HashMultimap.create();
		
//		System.out.println("got "+jumps.size()+" jumps");
		
		for (Jump jump : jumps) {
//			System.out.println("\tadding "+jump);
//			Preconditions.checkState(clusters.contains(jump.fromCluster));
//			Preconditions.checkState(clusters.contains(jump.toCluster));
			connectedParents.add(new IDPairing(jump.fromCluster.parentSectionID, jump.toCluster.parentSectionID));
			connectedParents.add(new IDPairing(jump.toCluster.parentSectionID, jump.fromCluster.parentSectionID));
			jump.fromCluster.addConnection(jump);
			Jump reverse = jump.reverse();
			jump.toCluster.addConnection(reverse);
			jumpsFrom.put(jump.fromSection, jump);
			jumpsFrom.put(reverse.fromSection, reverse);
//			System.out.println("\t\tfrom has "+jump.fromCluster.getConnections().size()+", to has "+jump.toCluster.getConnections().size());
		}
		
//		for (FaultSubsectionCluster cluster : clusters)
//			System.out.println("Cluster "+cluster+" has "+cluster.getConnections().size()+" jumps");
		
		connectionsAdded = true;
		return jumps.size();
	}
	
	private class ConnSearchCallable implements Callable<List<Jump>> {
		
		private FaultSubsectionCluster cluster1;
		private FaultSubsectionCluster cluster2;

		public ConnSearchCallable(FaultSubsectionCluster cluster1, FaultSubsectionCluster cluster2) {
			super();
			this.cluster1 = cluster1;
			this.cluster2 = cluster2;
		}

		@Override
		public List<Jump> call() throws Exception {
			return buildPossibleConnections(cluster1, cluster2);
		}
		
	}
	
	/**
	 * Builds a list of all possible jumps between all full clusters (this will typically include each unique jump
	 * both forwards and backwards)
	 * 
	 * @param clusters
	 * @return
	 */
	public List<Jump> getAllPossibleJumps() {
		// force it to populate connections if not yet populated
		getClusters();
		
		List<Jump> jumps = new ArrayList<>();
		for (int c1=0; c1<clusters.size(); c1++) {
			FaultSubsectionCluster cluster1 = clusters.get(c1);
			jumps.addAll(cluster1.getConnections());
		}
		return jumps;
	}
	
	/**
	 * Returns a list of all possible jumps from the given section
	 * 
	 * @param clusters
	 * @return
	 */
	public Collection<Jump> getJumpsFrom(FaultSection sect) {
		// force it to populate connections if not yet populated
		getClusters();
		
		return jumpsFrom.get(sect);
	}
	
	/**
	 * Returns a list of any possible connection(s) between the given clusters
	 * 
	 * @param from
	 * @param to
	 * @return List of allowed jumps, can be empty or null if no connections possible
	 */
	protected abstract List<Jump> buildPossibleConnections(FaultSubsectionCluster from, FaultSubsectionCluster to);
	
	/**
	 * 
	 * @param parentID1
	 * @param parentID2
	 * @return true if there is any allowed direct connection between the given parent section IDs
	 */
	public boolean areParentSectsConnected(int parentID1, int parentID2) {
		// force it to populate connections if not yet populated
		getClusters();
		return connectedParents.contains(new IDPairing(parentID1, parentID2));
	}
	
	/**
	 * @return max allowed jump distance, or +inf if no limit
	 */
	public abstract double getMaxJumpDist();
	
	public static class ConnStratTypeAdapter extends TypeAdapter<ClusterConnectionStrategy> {

		private List<? extends FaultSection> subSects;
		private SectionDistanceAzimuthCalculator distCalc;

		public ConnStratTypeAdapter(List<? extends FaultSection> subSects, SectionDistanceAzimuthCalculator distCalc) {
			this.subSects = subSects;
			this.distCalc = distCalc;
		}

		@Override
		public void write(JsonWriter out, ClusterConnectionStrategy value) throws IOException {
			// serialize based on clusters list
			out.beginObject();
			out.name("name").value(value.getName());
			if (Double.isFinite(value.getMaxJumpDist()))
				out.name("maxJumpDist").value(value.getMaxJumpDist());
			out.name("clusters").beginArray();
			for (FaultSubsectionCluster cluster : value.getClusters())
				cluster.writeJSON(out);
			out.endArray();
			out.endObject();
		}

		@Override
		public ClusterConnectionStrategy read(JsonReader in) throws IOException {
			in.beginObject();
			String name = null;
			double maxJumpDist = Double.POSITIVE_INFINITY;
			List<FaultSubsectionCluster> clusters = null;
			
			while (in.hasNext()) {
				String jsonName = in.nextName();
				switch (jsonName) {
				case "name":
					name = in.nextString();
					break;
				case "maxJumpDist":
					maxJumpDist = in.nextDouble();
					break;
				case "clusters":
					clusters = loadClusters(in);
					break;
				default:
					throw new IllegalStateException("Unexpected JSON with name="+jsonName);
				}
			}
			in.endObject();
			Preconditions.checkNotNull(clusters);
			return new PrecomputedClusterConnectionStrategy(name, subSects, clusters, maxJumpDist, distCalc);
		}
		
		private List<FaultSubsectionCluster> loadClusters(JsonReader in) throws IOException {
			List<FaultSubsectionCluster> clusters = new ArrayList<>();
			in.beginArray();
			
			Map<Integer, FaultSubsectionCluster> parentsToClusters = new HashMap<>();
			// can't fill in jumps until all clusters have been loaded
			Map<FaultSubsectionCluster, List<JumpStub>> clusterJumps = new HashMap<>();
			
			while (in.hasNext()) {
				FaultSubsectionCluster cluster = FaultSubsectionCluster.readJSON(in, subSects, clusterJumps, null);

				clusters.add(cluster);
				parentsToClusters.put(cluster.parentSectionID, cluster);
			}
			
			in.endArray();
			
			// now finalize jumps
			FaultSubsectionCluster.buildJumpsFromStubs(clusters, clusterJumps);
			return clusters;
		}
		
	}
	
	public static void main(String[] args) throws IOException {
		FaultModels fm = FaultModels.FM3_1;
		DeformationModels dm = fm.getFilterBasis();
		
		DeformationModelFetcher dmFetch = new DeformationModelFetcher(fm, dm,
				null, 0.1);
		
		List<FaultSection> parentSects = fm.getFaultSections();
		List<? extends FaultSection> subSects = dmFetch.getSubSectionList();
		
		SectionDistanceAzimuthCalculator distAzCalc = new SectionDistanceAzimuthCalculator(subSects);
		File cacheFile = new File("/tmp/dist_az_cache_"+fm.encodeChoiceString()+"_"+subSects.size()
			+"_sects_"+parentSects.size()+"_parents.csv");
		if (cacheFile.exists()) {
			System.out.println("Loading dist/az cache from "+cacheFile.getAbsolutePath());
			distAzCalc.loadCacheFile(cacheFile);
		}
		
		DistCutoffClosestSectClusterConnectionStrategy connStrat =
				new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, 5d);
		
		GsonBuilder builder = new GsonBuilder();
		builder.setPrettyPrinting();
		builder.registerTypeHierarchyAdapter(ClusterConnectionStrategy.class,
				new ConnStratTypeAdapter(subSects, distAzCalc));
		
		Gson gson = builder.create();
		
		String json = gson.toJson(connStrat);
		System.out.println(json);
		System.out.println("Loading...");
		ClusterConnectionStrategy loaded = gson.fromJson(json, ClusterConnectionStrategy.class);
		System.out.println("Validating...");
		List<FaultSubsectionCluster> clusters1 = connStrat.getClusters();
		List<FaultSubsectionCluster> clusters2 = loaded.getClusters();
		Preconditions.checkState(clusters1.size() == clusters2.size());
		for (int i=0; i<clusters1.size(); i++) {
			FaultSubsectionCluster c1 = clusters1.get(i);
			FaultSubsectionCluster c2 = clusters2.get(i);
			Preconditions.checkState(c1.equals(c2),
					"Cluster mismatch at %s:\n\tOrig: %s\n\tLoaded", i, c1, c2);
			List<Jump> jumps1 = c1.getConnections();
			List<Jump> jumps2 = c2.getConnections();
			if (jumps1 == null) {
				Preconditions.checkState(jumps2 == null);
			} else {
				Preconditions.checkState(jumps1.size() == jumps2.size());
				for (int j=0; j<jumps1.size(); j++)
					Preconditions.checkState(jumps1.get(j).equals(jumps2.get(j)));
			}
		}
		System.out.println("DONE");
	}

}
