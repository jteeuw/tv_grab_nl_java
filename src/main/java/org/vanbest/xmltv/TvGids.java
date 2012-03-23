package org.vanbest.xmltv;

/*
  Copyright (c) 2012 Jan-Pascal van Best <janpascal@vanbest.org>

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  The full license text can be found in the LICENSE file.
*/

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.ezmorph.MorpherRegistry;
import net.sf.ezmorph.ObjectMorpher;
import net.sf.ezmorph.object.DateMorpher;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.util.JSONUtils;

public class TvGids {

	static String channels_url="http://www.tvgids.nl/json/lists/channels.php";
	static String programme_base_url="http://www.tvgids.nl/json/lists/programs.php";
	static String detail_base_url = "http://www.tvgids.nl/json/lists/program.php";
	static String html_detail_base_url = "http://www.tvgids.nl/programma/";

	Config config;
	ProgrammeCache cache;
	static boolean initialised = false;
	int fetchErrors = 0;
	int cacheHits = 0;
	int cacheMisses = 0;
	
	public TvGids(Config config) {
		this.config = config;
		cache = new ProgrammeCache(config.cacheFile);
		if ( ! initialised ) {
			init();
			initialised = true;
		}
	}
	
	public static void init() {
		String[] formats = {"yyyy-MM-dd HH:mm:ss"};
		MorpherRegistry registry = JSONUtils.getMorpherRegistry();
		registry.registerMorpher( new DateMorpher(formats, new Locale("nl")));
		registry.registerMorpher( new ObjectMorpher() {
			 public Object morph(Object value) {
				 String s = (String) value;
				 return org.apache.commons.lang.StringEscapeUtils.unescapeHtml(s);
			 }
			 public Class morphsTo() {
				 return String.class;
			 }
			 public boolean supports(Class clazz) {
				 return clazz == String.class;
			 }
		}, true);
	}
	
	public void close() throws FileNotFoundException, IOException {
		cache.close();
	}

	public List<Channel> getChannels() {
		List<Channel> result = new ArrayList<Channel>(10);
		URL url = null;
		try {
			url = new URL(channels_url);
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		StringBuffer json = new StringBuffer();
		try {

			BufferedReader reader = new BufferedReader( new InputStreamReader( url.openStream()));

			String s;
			while ((s = reader.readLine()) != null) json.append(s);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (config.logJSON()) System.out.println(json.toString());
		JSONArray jsonArray = JSONArray.fromObject( json.toString() );  
		// System.out.println( jsonArray );  
		
		for( int i=0; i<jsonArray.size(); i++ ) {
			JSONObject zender = jsonArray.getJSONObject(i);
			//System.out.println( "id: " + zender.getString("id"));
			//System.out.println( "name: " + zender.getString("name"));
			Channel c = new Channel(zender.getInt("id"), zender.getString("name"), zender.getString("name_short"));
			c.setIconUrl("http://tvgidsassets.nl/img/channels/53x27/" + c.id + ".png");
			c.fixup();
			result.add(c);
		}

		return result;
		
	}
	
	public static URL programmeUrl(List<Channel> channels, int day) throws Exception {
		StringBuilder s = new StringBuilder(programme_base_url);
		if (channels.size() < 1) {
			throw new Exception("should have at least one channel");
		}
		s.append("?channels=");
		boolean first = true;
		for(Channel i: channels) {
			if (first) {
				s.append(i.id);
				first = false;
			} else {
				s.append(","+i.id);
			}
		}
		s.append("&day=");
		s.append(day);
		
		return new URL(s.toString());
	}
	
	public static URL JSONDetailUrl(String id) throws Exception {
		StringBuilder s = new StringBuilder(detail_base_url);
		s.append("?id=");
		s.append(id);
		return new URL(s.toString());
	}
		
	public static URL HTMLDetailUrl(String id) throws Exception {
		StringBuilder s = new StringBuilder(html_detail_base_url);
		s.append(id);
		s.append("/");
		return new URL(s.toString());
	}
		
	public Set<Programme> getProgrammes(List<Channel> channels, int day, boolean fetchDetails) throws Exception {
		Set<Programme> result = new HashSet<Programme>();
		URL url = programmeUrl(channels, day);

		JSONObject jsonObject = fetchJSON(url);  
		//System.out.println( jsonObject );  
		
		for( Channel c: channels) {
			JSON ps = (JSON) jsonObject.get(""+c.id);
			if ( ps.isArray() ) {
				JSONArray programs = (JSONArray) ps;
				for( int i=0; i<programs.size(); i++ ) {
					JSONObject programme = programs.getJSONObject(i);
					Programme p = programmeFromJSON(programme, fetchDetails);
					p.channel = c;
					result.add( p );
				}
			} else { 
				JSONObject programs = (JSONObject) ps;
				for( Object o: programs.keySet() ) {
					JSONObject programme = programs.getJSONObject(o.toString());
					Programme p = programmeFromJSON(programme, fetchDetails);
					p.channel = c;
					result.add( p );
				}
			}
		}

		return result;
	}
	
	private Programme programmeFromJSON(JSONObject programme, boolean fetchDetails) throws Exception {
		Programme p = (Programme) JSONObject.toBean(programme, Programme.class);
		p.fixup(config);
		if (fetchDetails) {
			fillDetails(p);
		}
		if(config.logProgrammes()) {
			System.out.println(p.toString());
		}
		return p;
	}

	private String fetchURL(URL url) throws Exception {
		Thread.sleep(config.niceMilliseconds);
		StringBuffer buf = new StringBuffer();
		try {
			BufferedReader reader = new BufferedReader( new InputStreamReader( url.openStream()));
			String s;
			while ((s = reader.readLine()) != null) buf.append(s);
		} catch (IOException e) {
			fetchErrors++;
			throw new Exception("Error getting program data from url " + url, e);
		}
		return buf.toString();  
	}

	private JSONObject fetchJSON(URL url) throws Exception {
		String json = fetchURL(url);
		if (config.logJSON()) System.out.println(json);
		return JSONObject.fromObject( json );  
	}

	private void fillDetails(Programme p) throws Exception {
		Pattern progInfoPattern = Pattern.compile("prog-info-content.*prog-info-footer", Pattern.DOTALL);
		Pattern infoLinePattern = Pattern.compile("<li><strong>(.*?):</strong>(.*?)</li>");
		Pattern HDPattern = Pattern.compile("HD \\d+[ip]?");
		Pattern kijkwijzerPattern = Pattern.compile("<img src=\"http://tvgidsassets.nl/img/kijkwijzer/.*?\" alt=\"(.*?)\" />");

		p.details = cache.getDetails(p.db_id);
		if ( p.details == null ) {
			cacheMisses++;
			
			URL url = JSONDetailUrl(p.db_id);
			JSONObject json = fetchJSON(url);
			p.details = (ProgrammeDetails) JSONObject.toBean(json, ProgrammeDetails.class);
			
			url = HTMLDetailUrl(p.db_id);
			String clob=fetchURL(url);
			//System.out.println("clob:");
			//System.out.println(clob);
			Matcher m = progInfoPattern.matcher(clob);
			if (m.find()) {
				String progInfo = m.group();
				//System.out.println("progInfo");
				//System.out.println(progInfo);
				Matcher m2 = infoLinePattern.matcher(progInfo);
				while (m2.find()) {
					//System.out.println("    infoLine: " + m2.group());
					//System.out.println("         key: " + m2.group(1));
					//System.out.println("       value: " + m2.group(2));
					String key = m2.group(1);
					String value = m2.group(2);
					switch(key.toLowerCase()) {
					case "bijzonderheden":
						String[] list = value.split(",");
						for( String item: list) {
							if (item.toLowerCase().contains("teletekst")) {
								p.details.subtitle_teletekst = true;
							} else if (item.toLowerCase().contains("breedbeeld")) {
								p.details.breedbeeld = true;
							} else if (value.toLowerCase().contains("zwart")) {
								p.details.blacknwhite = true;
							} else if (value.toLowerCase().contains("stereo")) {
								p.details.stereo = true;
							} else if (value.toLowerCase().contains("herhaling")) {
								p.details.herhaling = true;
							} else {
								Matcher m3 = HDPattern.matcher(value);
								if (m3.find()) {
									p.details.quality = m3.group();
								} else {
									if (!config.quiet) System.out.println("  Unknown value in 'bijzonderheden': " + item);
								}
							}
						}
						break;
					}
					Matcher m3 = kijkwijzerPattern.matcher(progInfo);
					List<String> kijkwijzer = new ArrayList<String>();
					while (m3.find()) {
						kijkwijzer.add(m3.group(1));
					}
					if (!kijkwijzer.isEmpty()) {
						// log.debug()
						// System.out.println("  (kijkwijzer): " + p.details.kijkwijzer);
						// System.out.println("    kijkwijzer: " + kijkwijzer);
					}
				}
			}
			
			p.details.fixup(p, config.quiet);
			cache.add(p.db_id, p.details);
		} else {
			cacheHits++;
		}
	}
}
