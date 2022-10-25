package scratch.UCERF3.inversion.laughTest;

import java.util.List;
import java.util.Map;

import org.opensha.commons.util.IDPairing;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityResult;
import org.opensha.sha.faultSurface.FaultSection;

/**
 * This filter keeps track of the cumulative jump distance along a rupture and stops the
 * rupture at the given threshold.
 * 
 * @author kevin
 *
 */
public class CumulativeJumpDistFilter extends AbstractPlausibilityFilter {
	
	private Map<IDPairing, Double> distances;
	private double maxCmlJumpDist;
	
	public CumulativeJumpDistFilter(Map<IDPairing, Double> distances, double maxCmlJumpDist) {
		this.distances = distances;
		this.maxCmlJumpDist = maxCmlJumpDist;
	}

	@Override
	public PlausibilityResult applyLastSection(List<? extends FaultSection> rupture,
			List<IDPairing> pairings, List<Integer> junctionIndexes) {
		double dist = 0;
		for (int junctionIndex : junctionIndexes) {
			// index+1 here because pairing list starts with the second section
			IDPairing pair = pairings.get(junctionIndex+1);
			dist += distances.get(pair);
		}
		if (dist <= maxCmlJumpDist)
			return PlausibilityResult.PASS;
		return PlausibilityResult.FAIL_HARD_STOP;
	}

	@Override
	public boolean isApplyJunctionsOnly() {
		return true;
	}
	
	@Override
	public String getName() {
		return "Cumulative Jump Dist Filter";
	}
	
	@Override
	public String getShortName() {
		return "CumJumpDist";
	}

}
