package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.IDPairing;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Probability calculator that occurs independently at each jump
 * 
 * @author kevin
 *
 */
public interface JumpProbabilityCalc extends RuptureProbabilityCalc {
	
	/**
	 * This computes the probability of this jump occurring conditioned on the rupture
	 * up to that point having occurred, and relative to the rupture arresting rather
	 * than taking that jump.
	 * 
	 * @param fullRupture
	 * @param jump
	 * @param verbose
	 * @return conditional jump probability
	 */
	public abstract double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose);

	@Override
	public default double calcRuptureProb(ClusterRupture rupture, boolean verbose) {
		double prob = 1d;
		for (Jump jump : rupture.getJumpsIterable()) {
			double jumpProb = calcJumpProbability(rupture, jump, verbose);
			if (verbose)
				System.out.println(getName()+": "+jump+", P="+jumpProb);
			prob *= jumpProb;
			if (prob == 0d && !verbose)
				// don't bother continuing
				break;
		}
		return prob;
	}
	
	public static interface DistDependentJumpProbabilityCalc extends JumpProbabilityCalc {
		
		/**
		 * This computes the probability of this jump occurring conditioned on the rupture
		 * up to that point having occurred, and relative to the rupture arresting rather
		 * than taking that jump.
		 * 
		 * @param fullRupture
		 * @param jump
		 * @param verbose
		 * @return conditional jump probability
		 */
		public default double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			return calcJumpProbability(jump.distance);
		}
		
		/**
		 * This computes the probability of this jump occurring conditioned on the rupture
		 * up to that point having occurred, and relative to the rupture arresting rather
		 * than taking that jump, as a function of jump distance only
		 * 
		 * @param distance
		 * @return conditional jump probability
		 */
		public abstract double calcJumpProbability(double distance);
	}
	
	public static interface BinaryJumpProbabilityCalc extends BinaryRuptureProbabilityCalc, JumpProbabilityCalc {
		
		public boolean isJumpAllowed(ClusterRupture fullRupture, Jump jump, boolean verbose);

		@Override
		default double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			if (isJumpAllowed(fullRupture, jump, verbose))
				return 1d;
			return 0d;
		}

		@Override
		default boolean isRupAllowed(ClusterRupture fullRupture, boolean verbose) {
			for (Jump jump : fullRupture.getJumpsIterable())
				if (!isJumpAllowed(fullRupture, jump, verbose))
					return false;
			return true;
		}

		@Override
		default double calcRuptureProb(ClusterRupture rupture, boolean verbose) {
			return BinaryRuptureProbabilityCalc.super.calcRuptureProb(rupture, verbose);
		}
	}
	
	public class MultiProduct implements JumpProbabilityCalc {
		
		@JsonAdapter(GenericArrayJumpProbCalcAdapter.class)
		private JumpProbabilityCalc[] calcs;

		@SuppressWarnings("unused") // needed by gson to deserialize w/o hitting the varargs above
		public MultiProduct(JumpProbabilityCalc... calcs) {
			Preconditions.checkState(calcs.length > 1);
			this.calcs = calcs;
		}

		@Override
		public String getName() {
			return "Product of "+calcs.length+" models";
		}

		@Override
		public boolean isDirectional(boolean splayed) {
			for (RuptureProbabilityCalc calc : calcs)
				if (calc.isDirectional(splayed))
					return true;
			return false;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			double product = 1;
			for (JumpProbabilityCalc calc : calcs)
				product *= calc.calcJumpProbability(fullRupture, jump, verbose);
			return product;
		}
		
	}
	
	public class Minimum implements JumpProbabilityCalc {
		
		@JsonAdapter(GenericArrayJumpProbCalcAdapter.class)
		private JumpProbabilityCalc[] calcs;

		@SuppressWarnings("unused") // needed by gson to deserialize w/o hitting the varargs above
		public Minimum(JumpProbabilityCalc... calcs) {
			Preconditions.checkState(calcs.length > 1);
			this.calcs = calcs;
		}

		@Override
		public String getName() {
			return "Minimum from "+calcs.length+" models";
		}

		@Override
		public boolean isDirectional(boolean splayed) {
			for (RuptureProbabilityCalc calc : calcs)
				if (calc.isDirectional(splayed))
					return true;
			return false;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			double min = 1;
			for (JumpProbabilityCalc calc : calcs)
				min = Math.min(min, calc.calcJumpProbability(fullRupture, jump, verbose));
			return min;
		}
		
	}
	
	public class NoJumps implements BinaryJumpProbabilityCalc {

		@Override
		public boolean isDirectional(boolean splayed) {
			return false;
		}

		@Override
		public String getName() {
			return "No Jumps";
		}

		@Override
		public boolean isJumpAllowed(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			return false;
		}
		
	}
	
	public class LogicalAnd implements BinaryJumpProbabilityCalc {
		
		@JsonAdapter(GenericArrayBinaryJumpProbCalcAdapter.class)
		private BinaryJumpProbabilityCalc[] calcs;

		public LogicalAnd(BinaryJumpProbabilityCalc... calcs) {
			Preconditions.checkState(calcs.length > 1);
			this.calcs = calcs;
		}
		
		@SuppressWarnings("unused") // needed by gson to deserialize w/o hitting the varargs above
		private LogicalAnd() {};

		@Override
		public String getName() {
			return "Logical AND of "+calcs.length+" models";
		}

		@Override
		public boolean isDirectional(boolean splayed) {
			for (RuptureProbabilityCalc calc : calcs)
				if (calc.isDirectional(splayed))
					return true;
			return false;
		}

		@Override
		public boolean isJumpAllowed(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			for (BinaryJumpProbabilityCalc calc : calcs)
				if (!calc.isJumpAllowed(fullRupture, jump, verbose))
					return false;
			return true;
		}
	}
	
	public class HardcodedJumpProb implements JumpProbabilityCalc {
		
		private String name;
		private Map<IDPairing, Double> idsToProbs;
		private boolean parentSects;
		
		@JsonAdapter(GenericJumpProbCalcAdapter.class)
		private JumpProbabilityCalc fallback;

		public HardcodedJumpProb(String name, Map<IDPairing, Double> idsToProbs, boolean parentSects) {
			this(name, idsToProbs, parentSects, null);
		}

		public HardcodedJumpProb(String name, Map<IDPairing, Double> idsToProbs, boolean parentSects,
				JumpProbabilityCalc fallback) {
			this.name = name;
			this.idsToProbs = idsToProbs;
			this.parentSects = parentSects;
			this.fallback = fallback;
		}

		@Override
		public boolean isDirectional(boolean splayed) {
			return false;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			IDPairing pair = parentSects ?
					new IDPairing(jump.fromSection.getParentSectionId(), jump.toSection.getParentSectionId())
					: new IDPairing(jump.fromSection.getSectionId(), jump.toSection.getSectionId());
			Double prob = idsToProbs.get(pair);
			if (prob == null)
				prob = idsToProbs.get(pair.getReversed());
			if (verbose)
				System.out.println("Hardcoded probability for jump "+jump+": "+prob);
			if (prob != null)
				return prob;
			if (fallback == null)
				return 1d;
			return fallback.calcJumpProbability(fullRupture, jump, verbose);
		}
	}
	
	public static class HardcodedBinaryJumpProb implements BinaryJumpProbabilityCalc {
		
		private String name;
		private Set<IDPairing> ids;
		private boolean excludeMatches;
		private boolean parentSects;

		/**
		 * 
		 * @param name
		 * @param excludeMatches if true, jumps are not allowed between the given IDs, otherwise they're only allowed between those IDs
		 * @param ids (parent) section IDs this binary model applies to
		 * @param parentSects it true, ids are parent section IDs
		 */
		public HardcodedBinaryJumpProb(String name, boolean excludeMatches, Set<IDPairing> ids, boolean parentSects) {
			this.name = name;
			this.excludeMatches = excludeMatches;
			this.ids = ids;
			this.parentSects = parentSects;
		}

		@Override
		public boolean isDirectional(boolean splayed) {
			return false;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public boolean isJumpAllowed(ClusterRupture fullRupture, Jump jump, boolean verbose) {
			IDPairing pair = parentSects ?
					new IDPairing(jump.fromSection.getParentSectionId(), jump.toSection.getParentSectionId())
					: new IDPairing(jump.fromSection.getSectionId(), jump.toSection.getSectionId());
			boolean contains = ids.contains(pair) || ids.contains(pair.getReversed());
			if (verbose)
				System.out.println("Hardcoded jump contained "+jump+"? "+contains);
			if (contains) {
				// we govern this jump
				if (excludeMatches) {
					// we explicitly exclude this jump
					return false;
				} else {
					// else we allow it
					return true;
				}
			} else {
				// we don't govern this jump
				if (excludeMatches) {
					// it's not a match, so we should include it
					return true;
				} else {
					// else we only include jumps that we specified, so exclude this one
					return false;
				}
			}
		}
	}
	
	public static class GenericArrayJumpProbCalcAdapter extends TypeAdapter<JumpProbabilityCalc[]> {
		
		private GenericJumpProbCalcAdapter adapter = new GenericJumpProbCalcAdapter();

		@Override
		public void write(JsonWriter out, JumpProbabilityCalc[] calcs) throws IOException {
			out.beginArray();
			
			for (JumpProbabilityCalc calc : calcs)
				adapter.write(out, calc);
			
			out.endArray();
		}

		@Override
		public JumpProbabilityCalc[] read(JsonReader in) throws IOException {
			in.beginArray();
			
			List<JumpProbabilityCalc> calcs = new ArrayList<>();
			
			while (in.hasNext())
				calcs.add(adapter.read(in));
			
			in.endArray();
			
			return calcs.toArray(new JumpProbabilityCalc[0]);
		}
		
	}
	
	public static class GenericArrayBinaryJumpProbCalcAdapter extends TypeAdapter<BinaryJumpProbabilityCalc[]> {
		
		private GenericJumpProbCalcAdapter adapter = new GenericJumpProbCalcAdapter();

		@Override
		public void write(JsonWriter out, BinaryJumpProbabilityCalc[] calcs) throws IOException {
			out.beginArray();
			
			for (JumpProbabilityCalc calc : calcs)
				adapter.write(out, calc);
			
			out.endArray();
		}

		@Override
		public BinaryJumpProbabilityCalc[] read(JsonReader in) throws IOException {
			in.beginArray();
			
			List<BinaryJumpProbabilityCalc> calcs = new ArrayList<>();
			
			while (in.hasNext()) {
				JumpProbabilityCalc calc = adapter.read(in);
				Preconditions.checkState(calc instanceof BinaryJumpProbabilityCalc);
				calcs.add((BinaryJumpProbabilityCalc)calc);
			}
			
			in.endArray();
			
			return calcs.toArray(new BinaryJumpProbabilityCalc[0]);
		}
		
	}
	
	public static class GenericJumpProbCalcAdapter extends TypeAdapter<JumpProbabilityCalc> {
		
		Gson gson = new Gson();

		@Override
		public void write(JsonWriter out, JumpProbabilityCalc value) throws IOException {
			if (value == null) {
				out.nullValue();
				return;
			}
//			System.out.println("Serializing JumpProbCalc '"+value.getName()+"' of type "+value.getClass().getName());
			out.beginObject();

			out.name("type").value(value.getClass().getName());
			out.name("data");
			gson.toJson(value, value.getClass(), out);

			out.endObject();
		}

		@SuppressWarnings("unchecked")
		@Override
		public JumpProbabilityCalc read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;
			
			Class<? extends JumpProbabilityCalc> type = null;

			in.beginObject();

			Preconditions.checkState(in.nextName().equals("type"), "JSON 'type' object must be first");
			try {
				type = (Class<? extends JumpProbabilityCalc>) Class.forName(in.nextString());
			} catch (ClassNotFoundException | ClassCastException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}

			Preconditions.checkState(in.nextName().equals("data"), "JSON 'data' object must be second");
			JumpProbabilityCalc model = gson.fromJson(in, type);

			in.endObject();
			return model;
		}
		
	}
	
}