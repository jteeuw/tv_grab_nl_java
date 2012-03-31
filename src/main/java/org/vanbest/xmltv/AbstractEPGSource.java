package org.vanbest.xmltv;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Set;

import org.vanbest.xmltv.EPGSource.Stats;

public abstract class AbstractEPGSource implements EPGSource {

	protected Config config;
	protected TvGidsProgrammeCache cache;
	protected Stats stats = new Stats();
	
	public static final int MAX_FETCH_TRIES=5;

	public AbstractEPGSource(Config config) {
		this.config = config;
		cache = new TvGidsProgrammeCache(config.cacheFile);
	}

	public Set<TvGidsProgramme> getProgrammes(Channel channel, int day, boolean fetchDetails)
			throws Exception {
				ArrayList<Channel> list = new ArrayList<Channel>(2);
				list.add(channel);
				return getProgrammes(list, day, fetchDetails);
			}

	@Override
	public Stats getStats() {
		return stats;
	}

	@Override
	public void close() throws FileNotFoundException, IOException {
		cache.close();
	}

	protected String fetchURL(URL url) throws Exception {
		Thread.sleep(config.niceMilliseconds);
		StringBuffer buf = new StringBuffer();
		boolean done = false;
		IOException finalException = null;
		for(int count = 0; count<MAX_FETCH_TRIES && !done; count++) {
			try {
				BufferedReader reader = new BufferedReader( new InputStreamReader( url.openStream()));
				String s;
				while ((s = reader.readLine()) != null) buf.append(s);
				done = true;
			} catch (IOException e) {
				if (!config.quiet) {
					System.out.println("Error fetching from url " + url + ", count="+count);
				}
				finalException = e;
			}
		}
		if (!done) {
			stats.fetchErrors++;
			throw new Exception("Error getting program data from url " + url, finalException);
		}
		return buf.toString();  
	}


}