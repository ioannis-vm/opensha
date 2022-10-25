package scratch.UCERF3.erf.ETAS;

import org.opensha.commons.geo.Location;
import org.opensha.sha.earthquake.ProbEqkRupture;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupture;

import scratch.UCERF3.erf.ETAS.ETAS_Params.ETAS_ParameterList;


/**
 *
 * <b>Title:</b> EqkRupture<br>
 * <b>Description:</b> <br>
 * 
 * 
 * 
 * TODO resolve potential inconsistencies between:
 * 
 * 		perentID  and  parentRup.getID()
 * 		generation and parentRup.getGeneration()+1
 *
 * @author Ned Field
 * @version 1.0
 */

public class ETAS_EqkRupture extends ObsEqkRupture {
	
	private int id=-1, nthERF_Index=-1, generation=0, fssIndex=-1, gridNodeIndex=-1, cubeIndex=-1;
	private double distToParent=Double.NaN;
	private ETAS_EqkRupture parentRup=null;
	private int parentID=-1;	// TODO get rid of this
	private Location parentTriggerLoc = null;
	
	// trigger ruptures can have their own ETAS parameters. otherwise these will be NaN
	private double k = Double.NaN, c = Double.NaN, p = Double.NaN;
	
	public ETAS_EqkRupture() {};
	
	/**
	 * This sets the magnitude, surface, and rake from the given rupture
	 * (e.g., conversion from ProbEqkRupture to ETAS_EqkRupture).  All other
	 * values are default.  Note that the surface is a pointer to the original.
	 * @param probRup
	 */
	public ETAS_EqkRupture(ProbEqkRupture probRup) {
		this.setMag(probRup.getMag());
		this.setRuptureSurface(probRup.getRuptureSurface());
		this.setAveRake(probRup.getAveRake());
	}
	

	/**
	 * This sets the magnitude, surface, rake, origin time, and event ID from the given rupture
	 * (e.g., conversion from ObsEqkRupture to ETAS_EqkRupture).  All other
	 * values are default.  Note that the surface is a pointer to the original.
	 * @param probRup
	 */
	public ETAS_EqkRupture(ObsEqkRupture probRup) {
		this.setMag(probRup.getMag());
		this.setRuptureSurface(probRup.getRuptureSurface());
		this.setAveRake(probRup.getAveRake());
		this.setOriginTime(probRup.getOriginTime());
		this.setEventId(probRup.getEventId());
		this.setHypocenterLocation(probRup.getHypocenterLocation());
	}
	
	/**
	 * This sets only the values given; other things to be filled in later
	 * @param parentRup
	 * @param id
	 * @param originTimeInMillis
	 */
	public ETAS_EqkRupture(ETAS_EqkRupture parentRup, int id, long originTimeInMillis) {
		this.parentRup=parentRup;
		this.parentID = parentRup.getID();
		this.id=id;
		this.originTimeInMillis=originTimeInMillis;
		
	}

	
	/**
	 * The ID of the parent that spawned this primary aftershock
	 * Returns -1 if there is no parent
	 * @return
	 */
	public int getParentID() {
		return parentID;
//		if(parentRup instanceof ETAS_EqkRupture)
//			return ((ETAS_EqkRupture)parentRup).getID();
//		else
//			return -1;
	}
	
	
	public void setParentID(int parID) {
		if(parentRup != null && parentRup.getID() != parID)
			throw new RuntimeException("Parent ID conflict");
		parentID=parID;
	}
	
	
	public ETAS_EqkRupture getParentRup() {
		return parentRup;
	}
	
	
	/**
	 * This returns the distance to the parent (NaN if never set)
	 * @return
	 */
	public double getDistanceToParent() {
		return distToParent;
	}
	
	/**
	 * This sets the distance to parent
	 * TODO compute this and remove the setDistanceToParent method
	 * @param distToParent
	 */
	public void setDistanceToParent(double distToParent) {
		this.distToParent = distToParent;
	}
	
	/**
	 * The ID of this event
	 * @return
	 */
	public int getID() {
		return id;
	}

	
	/**
	 * Sets the ID of this event
	 * @return
	 */
	public void setID(int id) {
		this.id = id;
	}

	/**
	 * The ID of this event
	 * @return
	 */
	public int getGeneration() {
		return generation;
	}

	/**
	 * Sets the ID of this event
	 * @return
	 */
	public void setGeneration(int generation) {
		this.generation = generation;
	}


	public void setNthERF_Index(int nthERF_Index){
		this.nthERF_Index = nthERF_Index;
	}

	public int getNthERF_Index(){
		return nthERF_Index;
	}

	public int getFSSIndex() {
		return fssIndex;
	}

	public void setFSSIndex(int fssIndex) {
		this.fssIndex = fssIndex;
	}

	public int getGridNodeIndex() {
		return gridNodeIndex;
	}

	public void setGridNodeIndex(int gridNodeIndex) {
		this.gridNodeIndex = gridNodeIndex;
	}
	
	public int getCubeIndex() {
		return cubeIndex;
	}

	public void setCubeIndex(int cubeIndex) {
		this.cubeIndex = cubeIndex;
	}

	
	/**
	 * This is the point on the parent rupture surface from which this aftershock was triggered
	 * @param loc
	 */
	public void setParentTriggerLoc(Location loc) {
		parentTriggerLoc=loc;
	}
	
	/**
	 * This is the point on the parent rupture surface from which this aftershock was triggered
	 * @param loc
	 */
	public Location getParentTriggerLoc() {
		return parentTriggerLoc;
	}
	
	/**
	 * This returns the oldest ancestor of the given rupture,
	 * or null if this rupture is of generation 0 (or there is no
	 * parent rupture available)
	 * @return
	 */
	public ETAS_EqkRupture getOldestAncestor() {
		
		int gen = getGeneration();
		ETAS_EqkRupture oldestAncestor = getParentRup();
		if(gen==0 || oldestAncestor==null)
			return null;
		while(gen > 1) {
			if(oldestAncestor.getGeneration() != gen-1)	// test proper change in generation
				throw new RuntimeException("Problem with generation");
			gen = oldestAncestor.getGeneration();
			oldestAncestor = oldestAncestor.getParentRup();
		}
		// make sure it's spontaneous
		if(oldestAncestor.getGeneration() != 0)
			throw new RuntimeException("Problem with generation");
		return oldestAncestor;
	}
	
	/**
	 * Set custom k, p, and c values for this rupture 
	 * @param k
	 * @param p
	 * @param c
	 */
	public void setCustomETAS_Params(Double k, Double p, Double c) {
		this.k = k == null ? Double.NaN : k;
		this.p = p == null ? Double.NaN : p;
		this.c = c == null ? Double.NaN : c;
	}
	
	/**
	 * @return k parameter for this rupture
	 */
	public double getETAS_k() {
		return k;
	}

	/**
	 * @return c parameter for this rupture. 
	 */
	public double getETAS_c() {
		return c;
	}

	/**
	 * @return p parameter for this rupture. 
	 */
	public double getETAS_p() {
		return p;
	}
	
	/**
	 * Set k parameter for this rupture
	 */
	public void setETAS_k(double k) {
		this.k = k;
	}

	/**
	 * Set c parameter for this rupture. 
	 */
	public void setETAS_c(double c) {
		this.c = c;
	}

	/**
	 * Set p parameter for this rupture. 
	 */
	public void setETAS_p(double p) {
		this.p = p;
	}

	
	
	@Override
	public Object clone() {
		ObsEqkRupture parentClone = (ObsEqkRupture)super.clone();
		ETAS_EqkRupture clone = new ETAS_EqkRupture(parentClone);
		clone.id = id;
		clone.nthERF_Index = nthERF_Index;
		clone.generation = generation;
		clone.fssIndex = fssIndex;
		clone.gridNodeIndex = gridNodeIndex;
		clone.cubeIndex = cubeIndex;
		clone.distToParent = distToParent;
		clone.parentRup = parentRup;
		clone.parentID = parentID;
		clone.parentTriggerLoc = parentTriggerLoc;
		clone.k = k;
		clone.p = p;
		clone.c = c;
		return clone;
	}
	
}
