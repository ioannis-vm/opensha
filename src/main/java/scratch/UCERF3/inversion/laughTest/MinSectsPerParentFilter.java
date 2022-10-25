package scratch.UCERF3.inversion.laughTest;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.opensha.commons.util.IDPairing;
import org.opensha.refFaultParamDb.vo.FaultSectionPrefData;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityResult;
import org.opensha.sha.faultSurface.FaultSection;

import com.google.common.collect.Maps;

public class MinSectsPerParentFilter {
	
	/**
	 * This allows ruptures to continue but only passes when the last two sections
	 * in the rupture are on the same parent section
	 * @author kevin
	 *
	 */
	public static class ContinualFilter extends AbstractPlausibilityFilter {
		
		private int minSectsPerParent;
		public ContinualFilter(int minSectsPerParent) {
			this.minSectsPerParent = minSectsPerParent;
		}
		
		@Override
		public PlausibilityResult applyLastSection(List<? extends FaultSection> rupture,
				List<IDPairing> pairings, List<Integer> junctionIndexes) {
			int numJunctions = junctionIndexes.size();
			
			// make sure we aren't starting with a junction before minSectsPerParent
			if (rupture.size() >= minSectsPerParent && numJunctions > 0
					&& junctionIndexes.get(0) < minSectsPerParent)
				return PlausibilityResult.FAIL_HARD_STOP;
			
			// passes if there are at least n sections and the last section is not a junction
			if (rupture.size() >= minSectsPerParent && (numJunctions == 0
					|| junctionIndexes.get(numJunctions-1) < (rupture.size()-minSectsPerParent+1)))
				return PlausibilityResult.PASS;
			return PlausibilityResult.FAIL_FUTURE_POSSIBLE;
		}

		@Override
		public boolean isApplyJunctionsOnly() {
			return false;
		}
		
		@Override
		public String getName() {
			return "Min Sects Per Parent Continous Filter";
		}
		
		@Override
		public String getShortName() {
			return "SectsPerParent";
		}
	}
	
	/**
	 * This filter treats the case when two single sections could be added in a row,
	 * thus skipping the normal test. Ruptures can optionally be allowed to use a single
	 * section for a jump if that is the only way that the two parent sections are otherwise
	 * connected.
	 * 
	 * @author kevin
	 *
	 */
	public static class CleanupFilter extends AbstractPlausibilityFilter {
		
		private int minSectsPerParent;
		private boolean allowIfOnlyPath;
		private Map<Integer, Collection<Integer>> parentSectConnectionsMap;
		public CleanupFilter(int minSectsPerParent, boolean allowIfOnlyPath,
				List<List<Integer>> sectionConnectionsListList, List<? extends FaultSection> subSectData) {
			this.minSectsPerParent = minSectsPerParent;
			this.allowIfOnlyPath = allowIfOnlyPath;
			if (allowIfOnlyPath) {
				parentSectConnectionsMap = Maps.newHashMap();
				for (int sectIndex1=0; sectIndex1<sectionConnectionsListList.size(); sectIndex1++) {
					Integer parentID1 = subSectData.get(sectIndex1).getParentSectionId();
					Collection<Integer> parentConnections = parentSectConnectionsMap.get(parentID1);
					if (parentConnections == null) {
						parentConnections = new HashSet<Integer>();
						parentSectConnectionsMap.put(parentID1, parentConnections);
					}
					for (int sectIndex2 : sectionConnectionsListList.get(sectIndex1)) {
						Integer parentID2 = subSectData.get(sectIndex2).getParentSectionId();
						parentConnections.add(parentID2);
					}
				}
			}
		}
		
		@Override
		public PlausibilityResult applyLastSection(List<? extends FaultSection> rupture,
				List<IDPairing> pairings, List<Integer> junctionIndexes) {
			int numJunctions = junctionIndexes.size();
			// this will be called on new junctions only. we must make sure that this isn't
			// two junctions in a row, so we check that the Nth to last junction isn't at the
			// second to last index.
			
			if (rupture.size() < (minSectsPerParent+1))
				// +1 here because the sect on the new parent has been added
				return PlausibilityResult.FAIL_HARD_STOP;
			
			if (numJunctions < 2)
				// this is the first junction and it has enough sections so we're good
				return PlausibilityResult.PASS;
			
			// make sure that the previous junction is at least minSectsPerParent before
			// this new junction
			boolean pass = junctionIndexes.get(numJunctions-2) < (rupture.size()-minSectsPerParent);
			
			if (!pass && allowIfOnlyPath) {
				// see if they cannot otherwise connect. imagine section A, B, C where this rupture
				// has A connected to C through B, but with only one subsection. we want to let the
				// rupture through only if A cannot directly connect to C.
				
				// numJunctions-2 means the previous junction, then index-1 for the first section
				// at that junction (section A)
				int fromID = rupture.get(junctionIndexes.get(numJunctions-2)-1).getParentSectionId();
				// numJunctions-1 means the last junction, don't subtract fron index because we
				// want the second section at that junction (section C)
				int toID = rupture.get(junctionIndexes.get(numJunctions-1)).getParentSectionId();
				// return a pass if there is no direct connection from A to C
				if (parentSectConnectionsMap.get(fromID).contains(toID))
					return PlausibilityResult.FAIL_HARD_STOP;
				return PlausibilityResult.PASS;
			}
			return PlausibilityResult.PASS;
		}

		@Override
		public boolean isApplyJunctionsOnly() {
			return true;
		}
		
		@Override
		public String getName() {
			return "Min Sects Per Parent Cleanup Filter";
		}
		
		@Override
		public String getShortName() {
			return "SectsPerParentBridge";
		}
	}

}
