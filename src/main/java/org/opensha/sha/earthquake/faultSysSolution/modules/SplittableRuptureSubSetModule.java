package org.opensha.sha.earthquake.faultSysSolution.modules;

import org.opensha.commons.util.modules.ModuleHelper;
import org.opensha.commons.util.modules.OpenSHA_Module;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;

/**
 * Interface for a module that can be split into a subset of a {@link FaultSystemRupSet}. This is primarily used by
 * {@link FaultSystemRupSet#getForSectionSubSet(java.util.Collection)}.
 * @author kevin
 *
 * @param <E>
 */
@ModuleHelper
public interface SplittableRuptureSubSetModule<E extends OpenSHA_Module> extends OpenSHA_Module {
	
	/**
	 * Returns a view of this module that is specific to the given subset of a {@link FaultSystemRupSet}.
	 * 
	 * @param rupSubSet the subset {@link FaultSystemRupSet} containing a subset of the original sections and/or ruptures
	 * @param mappings gives mappings between original and new section and rupture IDs
	 * @return module for the given {@link FaultSystemRupSet} subset
	 */
	public E getForRuptureSubSet(FaultSystemRupSet rupSubSet, RuptureSubSetMappings mappings);

}
