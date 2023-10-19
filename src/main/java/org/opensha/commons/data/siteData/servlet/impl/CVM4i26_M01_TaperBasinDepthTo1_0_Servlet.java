package org.opensha.commons.data.siteData.servlet.impl;

import java.io.File;
import java.io.IOException;

import org.opensha.commons.data.siteData.SiteData;
import org.opensha.commons.data.siteData.impl.CVM4i26_M01_TaperBasinDepth;
import org.opensha.commons.data.siteData.servlet.AbstractSiteDataServlet;
import org.opensha.commons.util.ServerPrefUtils;

public class CVM4i26_M01_TaperBasinDepthTo1_0_Servlet extends
		AbstractSiteDataServlet<Double> {
	
	private static final File FILE = new File(ServerPrefUtils.SERVER_PREFS.getTomcatProjectDir(),
			CVM4i26_M01_TaperBasinDepth.DEPTH_1_0_FILE);
	
	public CVM4i26_M01_TaperBasinDepthTo1_0_Servlet() throws IOException {
		super(new CVM4i26_M01_TaperBasinDepth(SiteData.TYPE_DEPTH_TO_1_0, FILE, false));
	}
}
