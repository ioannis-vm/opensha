package org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl;

import java.util.ArrayList;
import java.util.List;

import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.ConstraintWeightingType;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.InversionConstraint;

import cern.colt.matrix.tdouble.DoubleMatrix2D;
import scratch.UCERF3.utils.U3SectionMFD_constraint;

/**
 * MFD Subsection nucleation constraint used in UCERF3 - constraints MFDs to conform to
 * an a priori section MFD. In UCERF3, we weakly constrained section MFDs to match UCERF2. Those
 * MFDs were irregularly spaced in an attempt to deal with empty magnitude bins.
 * 
 * @author Morgan Page & Kevin Milner
 *
 */
public class U3MFDSubSectNuclInversionConstraint extends InversionConstraint {
	
	public static final String NAME = "U3 Subsection Nucleation MFD";
	public static final String SHORT_NAME = "U3SectNuclMFD";
	
	private transient FaultSystemRupSet rupSet;
	private List<U3SectionMFD_constraint> constraints;

	public U3MFDSubSectNuclInversionConstraint(FaultSystemRupSet rupSet, double weight,
			List<U3SectionMFD_constraint> constraints) {
		super(NAME, SHORT_NAME, weight, false, ConstraintWeightingType.NORMALIZED);
		this.rupSet = rupSet;
		this.constraints = constraints;
	}
	
	public List<U3SectionMFD_constraint> getConstraints() {
		return constraints;
	}

	@Override
	public int getNumRows() {
		int numRows = 0;
		for (U3SectionMFD_constraint constraint : constraints)
			if (constraint != null)
				for (int i=0; i<constraint.getNumMags(); i++)
					if (constraint.getRate(i) > 0)
						numRows++;
		return numRows;
	}

	@Override
	public long encode(DoubleMatrix2D A, double[] d, int startRow) {
		long numNonZeroElements = 0;
		
		// Loop over all subsections
		int numSections = rupSet.getNumSections();
		int rowIndex = startRow;
		for (int sect=0; sect<numSections; sect++) {
			
			U3SectionMFD_constraint sectMFDConstraint = constraints.get(sect);
			if (sectMFDConstraint == null) continue; // Parent sections with Mmax<6 have no MFD constraint; skip these
			int numMagBins = sectMFDConstraint.getNumMags();
			List<Integer> rupturesForSect = rupSet.getRupturesForSection(sect);
			
			// Loop over MFD constraints for this subsection
			for (int magBin = 0; magBin<numMagBins; magBin++) {
				
				// Only include non-empty magBins in constraint
				if (sectMFDConstraint.getRate(magBin) > 0) {
					// Determine which ruptures are in this magBin
					List<Integer> rupturesForMagBin = new ArrayList<Integer>();
					for (int i=0; i<rupturesForSect.size(); i++) {
						double mag = rupSet.getMagForRup(rupturesForSect.get(i));
						if (sectMFDConstraint.isMagInBin(mag, magBin))
							rupturesForMagBin.add(rupturesForSect.get(i));
					}
					
					// Loop over ruptures in this subsection-MFD bin
					for (int i=0; i<rupturesForMagBin.size(); i++) {
						int rup  = rupturesForMagBin.get(i);
						double rupArea = rupSet.getAreaForRup(rup);
						double sectArea = rupSet.getAreaForSection(sect);
						setA(A, rowIndex, rup, weight * (sectArea / rupArea) / sectMFDConstraint.getRate(magBin));
						numNonZeroElements++;	
					}
					d[rowIndex] = weight;
					rowIndex++;
				}
			}
		}
		return numNonZeroElements;
	}

	@Override
	public void setRuptureSet(FaultSystemRupSet rupSet) {
		this.rupSet = rupSet;
	}

}
