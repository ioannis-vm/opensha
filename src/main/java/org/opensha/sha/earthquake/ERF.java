package org.opensha.sha.earthquake;

import java.util.List;
import java.util.function.UnaryOperator;

import org.opensha.sha.calc.disaggregation.DisaggregationSourceRuptureInfo;

/**
 * This is the base interface for an Earthquake Rupture Forecast</b> <br>
 * 
 * @author Nitin Gupta
 * @author Vipin Gupta
 * @version $Id: ERF.java 10806 2014-08-13 13:34:08Z field $
 */

public interface ERF extends BaseERF, Iterable<ProbEqkSource> {

	/**
	 * 
	 * @return the total number os sources
	 */
	public int getNumSources();

	/**
	 * Returns the list of all earthquake sources.
	 * @return list of all possible earthquake sources
	 */
	public List<ProbEqkSource> getSourceList();

	/**
	 * Returns the earthquake source at the supplied index.
	 * @param idx the index requested
	 * @return the source at <code>idx</code>
	 */
	public ProbEqkSource getSource(int idx);

	/**
	 * Returns the number of ruptures associated wit the source at the supplied
	 * index.
	 * @param idx the index requested
	 * @return the number of ruptures associated with the source at
	 *         <code>idx</code>
	 */
	public int getNumRuptures(int idx);

	/**
	 * Returns the rupture at the supplied source index and rupture index.
	 * @param srcIdx source index requested
	 * @param rupIdx rupture index requested
	 * @return the rupture at <code>rupIdx</code> associated with the source at
	 *         <code>srcIdx</code>
	 */
	public ProbEqkRupture getRupture(int srcIdx, int rupIdx);

	/**
	 * Returns a random set of ruptures.
	 * @return a random set of ruptures
	 */
	public List<EqkRupture> drawRandomEventSet();
	
	/**
	 * If non null, this can be used to consolidate disaggregation results into a consolidatd (and potentially more useful)
	 * list; this is most useful when many sources in an ERF rupture the same fault, and you want that fault to only
	 * be listed once. The default implementation returns null.
	 * @return consolidator or null
	 */
	public default UnaryOperator<List<DisaggregationSourceRuptureInfo>> getDisaggSourceConsolidator() {
		return null;
	}
	

}
