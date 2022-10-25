package org.opensha.commons.calc.magScalingRelations;

/**
 * <b>Title:</b>MagAreaRelDepthDep<br>
 *
 * <b>Description:</b>  This .  <p>
 *
 * @author Edward H. Field
 * @version 1.0
 */

public interface MagAreaRelDepthDep {

    /**
     * Computes the median magnitude from rupture area and down-dip width
     * @param area in km-squared
     * @param width in km-squared
     * @return median magnitude
     */
    public  double getWidthDepMedianMag(double area, double width);

}
