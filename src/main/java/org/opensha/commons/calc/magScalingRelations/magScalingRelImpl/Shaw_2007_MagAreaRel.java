package org.opensha.commons.calc.magScalingRelations.magScalingRelImpl;

import org.opensha.commons.calc.magScalingRelations.MagAreaRelationship;
import org.opensha.commons.data.function.ArbitrarilyDiscretizedFunc;

/**
 * <b>Title:</b>Shaw_2007_MagAreaRel<br>
 *
 * <b>Description:</b>  .<p>
 *
 * @author Edward H. Field
 * @version 1.0
 */

public class Shaw_2007_MagAreaRel extends MagAreaRelationship {

    final static String C = "Shaw_2007_MagAreaRel";
    public final static String NAME = "Shaw (2007)";
    ArbitrarilyDiscretizedFunc magAreaFunc = null;

    /**
     * Computes the median magnitude from rupture area.
     * @deprecated
     * @param area in km
     * @return median magnitude
     */
    @Deprecated
    public double getMedianMag(double area){
    	double alpha=6;
    	double h=15;
    	double numer= Math.max(1,Math.sqrt(area/(h*h)));
    	double denom= (1 + Math.max(1,(area/(alpha*h*h))))/2;
    	return  3.98 + Math.log(area)*lnToLog + 0.667*Math.log(numer/denom)*lnToLog;
    }

    /**
     * Gives the standard deviation for magnitude
     * @return standard deviation
     */
    public double getMagStdDev(){ return Double.NaN;}

    /**
     * Computes the median rupture area from magnitude
     * @param mag - moment magnitude
     * @return median area in km
     */
    public double getMedianArea(double mag){
    	
    	if(magAreaFunc == null) {
        	magAreaFunc = new ArbitrarilyDiscretizedFunc();
        	// area from 1 to 100000 (log area from 0 to 5)
        	for(int i=0; i<=1000; i++) {
        		double logArea = (double)i*5.5/1000.0;
        		double area = Math.pow(10,logArea);
        		double tempMag = getMedianMag(area);
        		magAreaFunc.set(area, tempMag);
        	}
        	/* debugging stuff 
        	System.out.println("firstMag="+magAreaFunc.getY(0));
        	System.out.println("lastMag="+magAreaFunc.getY(magAreaFunc.getNum()-1));
    		ArrayList funcs = new ArrayList();
    		funcs.add(magAreaFunc);
    		funcs.add(this.getMagAreaFunction(4, 0.1, 45));
    		GraphWindow graph = new GraphWindow(funcs, "Mag vs Area");   
*/
    	}
     	return magAreaFunc.getFirstInterpolatedX(mag);
   }

    /**
     * This returns NaN because the value is not available
     * @return standard deviation
     */
    public double getAreaStdDev() {return  Double.NaN;}

    /**
     * Returns the name of the object
     *
     */
    public String getName() {
      return NAME;
    }
    
	public static void main(String[] args) {
		Shaw_2007_MagAreaRel test = new Shaw_2007_MagAreaRel();
		test.getMedianArea(7);
	}
}

