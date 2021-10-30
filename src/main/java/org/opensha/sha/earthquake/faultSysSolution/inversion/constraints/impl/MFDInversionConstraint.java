package org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.opensha.commons.data.uncertainty.UncertainBoundedIncrMagFreqDist;
import org.opensha.commons.data.uncertainty.UncertainIncrMagFreqDist;
import org.opensha.commons.geo.Region;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.ConstraintWeightingType;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.InversionConstraint;
import org.opensha.sha.magdist.IncrementalMagFreqDist;

import com.google.common.base.Preconditions;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import cern.colt.matrix.tdouble.DoubleMatrix2D;

/**
 * Constraints the solution to match the given MFD constraints, which can be region specific.
 * 
 * In UCERF3, we used an equality constraint up to large magnitudes, where we transitioned to
 * an inequality constraint, and used separate constraints for northern and southern CA. If inequality
 * is set to true, then it will be constrained not to exceed the given MFD(s).
 * 
 * TODO: just use MFDs, not the useless wrapper class
 * 
 * @author Morgan Page & Kevin Milner
 *
 */
public class MFDInversionConstraint extends InversionConstraint {
	
	private transient FaultSystemRupSet rupSet;
	@JsonAdapter(MFDsAdapter.class) private List<? extends IncrementalMagFreqDist> mfds;
	private HashSet<Integer> excludeRupIndexes;
	
	public MFDInversionConstraint(FaultSystemRupSet rupSet, double weight, boolean inequality,
			List<? extends IncrementalMagFreqDist> mfds) {
		this(rupSet, weight, inequality, ConstraintWeightingType.NORMALIZED, mfds);
	}

	public MFDInversionConstraint(FaultSystemRupSet rupSet, double weight, boolean inequality,
			ConstraintWeightingType weightingType, List<? extends IncrementalMagFreqDist> mfds) {
		this(rupSet, weight, inequality, weightingType, mfds, null);
	}

	public MFDInversionConstraint(FaultSystemRupSet rupSet, double weight, boolean inequality,
			ConstraintWeightingType weightingType, List<? extends IncrementalMagFreqDist> mfds,
			HashSet<Integer> excludeRupIndexes) {
		super(weightingType.applyNamePrefix("MFD "+(inequality ? "Inequality" : "Equality")),
				weightingType.applyShortNamePrefix("MFD"+(inequality ? "Inequality" : "Equality")),
				weight, inequality, weightingType);
		this.rupSet = rupSet;
		this.mfds = mfds;
		if (weightingType == ConstraintWeightingType.NORMALIZED_BY_UNCERTAINTY) {
			for (IncrementalMagFreqDist mfd : mfds)
				Preconditions.checkNotNull(mfd instanceof UncertainIncrMagFreqDist,
						"Uncertain MFD instances (with standard deviations) must be supplied for uncertainty weighting");
		}
		this.excludeRupIndexes = excludeRupIndexes;
	}
	
	/**
	 * Calculates the number of rows needed for MFD constraints, ignoring any bins that are above or below the
	 * magnitude range of the rupture set.
	 * 
	 * @param constraints
	 * @param rupSet
	 * @return number of rows needed
	 */
	static int getNumRows(List<? extends IncrementalMagFreqDist> mfds, FaultSystemRupSet rupSet) {
		int totalNumMagFreqConstraints = 0;
		for (IncrementalMagFreqDist mfd : mfds) {
			// Find number of rows used for MFD constraint
			// only include mag bins between minimum and maximum magnitudes in rupture set
			totalNumMagFreqConstraints += mfd.getClosestXIndex(rupSet.getMaxMag())
					- mfd.getClosestXIndex(rupSet.getMinMag()) + 1;
		}
		return totalNumMagFreqConstraints;
	}

	@Override
	public int getNumRows() {
		return getNumRows(mfds, rupSet);
	}

	@Override
	public long encode(DoubleMatrix2D A, double[] d, int startRow) {
		long numNonZeroElements = 0;
		// Loop over all MFD constraints in different regions
		int numRuptures = rupSet.getNumRuptures();
		for (int i=0; i < mfds.size(); i++) {
			IncrementalMagFreqDist mfd = mfds.get(i);
			Region region = mfd.getRegion();
//			Region region = mfd.getRegion(); // TODO Switch to useing MFD region, abandon wrapper
			double[] fractRupsInside = rupSet.getFractRupsInsideRegion(region, false);
			int minMagIndex = mfd.getClosestXIndex(rupSet.getMinMag());
			int maxMagIndex = mfd.getClosestXIndex(rupSet.getMaxMag());
			
			double[] targets = new double[mfd.size()];
			double[] scales = new double[targets.length];
			
			for (int j=0; j<targets.length; j++) {
				double rate = mfd.getY(j);
				if (rate == 0d) {
					targets[j] = 0d;
					scales[j] = 0d;
				} else {
					double stdDev = mfd instanceof UncertainIncrMagFreqDist ?
							((UncertainIncrMagFreqDist)mfd).getStdDev(j) : 0d;
					scales[j] = weightingType.getA_Scalar(rate, stdDev);
					targets[j] = weightingType.getD(rate, stdDev);
				}
			}
			
			for(int rup=0; rup<numRuptures; rup++) {
				double mag = rupSet.getMagForRup(rup);
				double fractRupInside = fractRupsInside[rup];
				if (fractRupInside > 0 && mag>mfd.getMinX()-mfd.getDelta()/2.0 && mag<mfd.getMaxX()+mfd.getDelta()/2.0) {
					if (excludeRupIndexes != null && excludeRupIndexes.contains(rup))
						continue;
					int magIndex = mfd.getClosestXIndex(mag);
					Preconditions.checkState(magIndex >= minMagIndex && magIndex <= maxMagIndex);
					int rowIndex = startRow + magIndex - minMagIndex;
					
					if (targets[magIndex] == 0d) {
						setA(A, rowIndex, rup, 0d);
					} else {
						setA(A, rowIndex, rup, weight * fractRupInside * scales[magIndex]);
						numNonZeroElements++;
					}
				}
			}
			for (int magIndex=minMagIndex; magIndex<=maxMagIndex; magIndex++) {
				int rowIndex = startRow + magIndex - minMagIndex;
				d[rowIndex] = weight*targets[magIndex];
			}
			// move startRow to point after this constraint
			startRow += (maxMagIndex - minMagIndex) + 1;
		}
		return numNonZeroElements;
	}

	@Override
	public void setRuptureSet(FaultSystemRupSet rupSet) {
		this.rupSet = rupSet;
	}
	
	private static IncrementalMagFreqDist.Adapter regularAdapter = new IncrementalMagFreqDist.Adapter();
	private static UncertainIncrMagFreqDist.Adapter uncertAdapter = new UncertainIncrMagFreqDist.Adapter();
	private static UncertainBoundedIncrMagFreqDist.Adapter uncertBoundAdapter = new UncertainBoundedIncrMagFreqDist.Adapter();
	private static class MFDsAdapter extends TypeAdapter<List<? extends IncrementalMagFreqDist>> {

		@Override
		public void write(JsonWriter out, List<? extends IncrementalMagFreqDist> list) throws IOException {
			out.beginArray();
			for (IncrementalMagFreqDist mfd : list) {
				if (mfd instanceof UncertainBoundedIncrMagFreqDist)
					uncertBoundAdapter.write(out, (UncertainBoundedIncrMagFreqDist)mfd);
				else if (mfd instanceof UncertainIncrMagFreqDist)
					uncertAdapter.write(out, (UncertainIncrMagFreqDist)mfd);
				else
					regularAdapter.write(out, mfd);
			}
			out.endArray();
		}

		@Override
		public List<? extends IncrementalMagFreqDist> read(JsonReader in) throws IOException {
			List<IncrementalMagFreqDist> ret = new ArrayList<>();

			in.beginArray();
			while (in.hasNext())
				ret.add(readAsApplicable(in));
			in.endArray();
			
			return ret;
		}
		
		private IncrementalMagFreqDist readAsApplicable(JsonReader in) throws IOException {
			// this will work even if it's not uncertainty bounded
			UncertainBoundedIncrMagFreqDist func = uncertBoundAdapter.read(in);

			// see if it's actually uncertain
			if (func.getStdDevs() == null)
				// not uncertain
				return new IncrementalMagFreqDist(func);
			// see if it's really bounded
			if (func.getLower() == null) {
				// not bounded
				return new UncertainIncrMagFreqDist(func, func.getStdDevs());
			}
			// it's really uncertainty bounded
			return func;
		}
		
	}

}
