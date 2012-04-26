package org.vanbest.xmltv;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.vanbest.xmltv.EPGSource.Stats;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class RTL extends AbstractEPGSource implements EPGSource  {

	private static final String programme_url="http://www.rtl.nl/active/epg_data/dag_data/";
	private static final String detail_url="http://www.rtl.nl/active/epg_data/uitzending_data/";
	private static final String icon_url="http://www.rtl.nl/service/gids/components/vaste_componenten/";
	private static final int MAX_PROGRAMMES_PER_DAY = 9999;
	public static final String NAME="rtl.nl";
	
	String[] xmlKeys = {"zendernr", "pgmsoort", "genre", "bijvnwlanden", "ondertiteling", "begintijd", "titel", 
			"site_path", "wwwadres", "presentatie", "omroep", "eindtijd", "inhoud", "tt_inhoud", "alginhoud", "afl_titel", "kijkwijzer" };
	Map<String,Integer> xmlKeyMap = new HashMap<String,Integer>();
	
	static boolean debug = false;
	PrintWriter debugWriter;
		
	class RTLException extends Exception {
		public RTLException(String s) {
			super(s);
		}
	}
	
	class DateStatus {
		Date programDate;
		Calendar prevStartTime = null;
		final static int START_TIME=1;
		final static int END_TIME=2;
		public DateStatus(Date date) {
			reset(date);
		}
		public void reset(Date date) {
			this.programDate = date;
			this.prevStartTime = null;
		}
	}
	
	class DescStatus {
		String inhoud;
		String alginhoud;
		String tt_inhoud;
	}
    
	public RTL(int sourceId, Config config) {
		super(sourceId, config);
		if(debug) {
			for(int i=0; i<xmlKeys.length; i++) {
				xmlKeyMap.put(xmlKeys[i], i);
			}
		}
	}
	
	public String getName() {
		return NAME;
	}
	
	public List<Channel> getChannels() {
		List<Channel> result = new ArrayList<Channel>(10);

		URL url = null;
		try {
			url = new URL(programme_url+"1");
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Document xml = null;
		try {
			xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.openStream());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Element root = xml.getDocumentElement();
		String json = root.getTextContent();
		JSONObject o = JSONObject.fromObject( json );
		for( Object k: o.keySet()) {
			JSONArray j = (JSONArray) o.get(k);
			String id = genericChannelId(k.toString());
			String name = (String) j.get(0);
			String icon = icon_url+id+".gif";
			
			Channel c = Channel.getChannel(getId(), id, name, icon);
			result.add(c);
		}

		Collections.sort(result, new Comparator<Channel>() {
			public int compare(Channel o1, Channel o2) {
				if (o1.source==o2.source) {
					int c1=Integer.parseInt(o1.id);
					int c2=Integer.parseInt(o2.id);
					return (c1==c2 ? 0 : ((c1<c2)?-1:1) );
				} else  {
					return o1.source<o2.source?-1:1;
				}
			}
		});
		return result;
	}
	
	private String genericChannelId(String jsonid) {
		return jsonid.replaceAll("^Z", ""); // remove initial Z
	}
	
	/*
	 * <?xml version="1.0" encoding="iso-8859-1" ?>
	 * <uitzending_data>
	 *   <uitzending_data_item>
	 *     <zendernr>5</zendernr>
	 *     <pgmsoort>Realityserie</pgmsoort>
	 *     <genre>Amusement</genre>
	 *     <bijvnwlanden></bijvnwlanden>
	 *     <ondertiteling></ondertiteling>
	 *     <begintijd>05:00</begintijd>
	 *     <titel>Marriage Under Construction</titel>
	 *     <site_path>0</site_path>
	 *     <wwwadres></wwwadres>
	 *     <presentatie></presentatie>
	 *     <omroep></omroep>
	 *     <eindtijd>06:00</eindtijd>
	 *     <inhoud></inhoud>
	 *     <tt_inhoud>Een jong stel wordt gevolgd bij het zoeken naar, en vervolgens verbouwen en inrichten van, hun eerste huis. Dit verloopt uiteraard niet zonder slag of stoot.</tt_inhoud>
	 *     <alginhoud>Een jong stel wordt gevolgd bij het zoeken naar, en vervolgens verbouwen en inrichten van, hun eerste huis. Dit verloopt uiteraard niet zonder slag of stoot.</alginhoud>
	 *     <afl_titel></afl_titel>
	 *     <kijkwijzer></kijkwijzer>
	 *   </uitzending_data_item>
	 * </uitzending_data>

	 */
	private void fetchDetail(Programme prog, DateStatus dateStatus, String id) throws Exception {
		URL url = detailUrl(id);
		Thread.sleep(config.niceMilliseconds);
		Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.openStream());
		Element root = xml.getDocumentElement();
		if (root.hasAttributes()) {
			System.out.println("Unknown attributes for RTL detail root node");
		}
		NodeList nodes = root.getChildNodes();
		DescStatus descStatus = new DescStatus();
		for(int i=0; i<nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (!n.getNodeName().equals("uitzending_data_item")) {
				System.out.println("Ignoring RTL detail, tag " + n.getNodeName() +", full xml:");
				Transformer t = TransformerFactory.newInstance().newTransformer();
				t.transform(new DOMSource(xml),new StreamResult(System.out));
				System.out.println();
				continue;
			}
			// we have a uitzending_data_item node
			NodeList subnodes = n.getChildNodes();
			String[] result = new String[xmlKeys.length];
			for( int j=0; j<subnodes.getLength(); j++) {
				try {
					if (debug) {
						Node sub = subnodes.item(j);
						String key = ((Element)sub).getTagName();
						int index = xmlKeyMap.get(key);
						String value = "\"" + sub.getTextContent().replaceAll("\\s", " ") + "\"";
						result[index] = value;
					}
					handleNode(prog, dateStatus, descStatus, subnodes.item(j));
				} catch (RTLException e) {
					System.out.println(e.getMessage());
					Transformer t = TransformerFactory.newInstance().newTransformer();
					t.transform(new DOMSource(xml),new StreamResult(System.out));
					System.out.println();
					continue;
				}
			}
			if (debug) {
				for(int k=0; k<result.length; k++) {
					debugWriter.print(result[k]);
					debugWriter.print(",");
				}
				debugWriter.println();
			}
		}
		StringBuilder description = new StringBuilder();
		if (descStatus.alginhoud!=null)	description.append(descStatus.alginhoud);
		if (descStatus.inhoud!=null) {
			if (description.length()!=0) {
				description.append("<p>");
			}
			description.append(descStatus.inhoud);
		}
		if (description.length()==0 && descStatus.tt_inhoud!=null) {
			// only use tt_inhoud if the other two are both empty, since it is almost
			// always a summary of those fields and others such as <presenter>
			description.append(descStatus.tt_inhoud);
		}
		prog.addDescription(description.toString());
	}

	
	private void handleNode(Programme prog, DateStatus dateStatus, DescStatus descStatus, Node n) throws RTLException, DOMException, SQLException {
		if (n.getNodeType() != Node.ELEMENT_NODE) {
			throw new RTLException("Ignoring non-element node " + n.getNodeName());
		}
		if (n.hasAttributes()) {
			throw new RTLException("Unknown attributes for RTL detail node " + n.getNodeName());
		}
		if (n.hasChildNodes()) {
			NodeList list = n.getChildNodes();
			for( int i=0; i<list.getLength(); i++) {
				if(list.item(i).getNodeType() == Node.ELEMENT_NODE) {
					throw new RTLException("RTL detail node " + n.getNodeName() + " has unexpected child element " + list.item(i).getNodeName());
				}
			}
		}
		Element e = (Element)n;
		String tag = e.getTagName();

		if (e.getTextContent().isEmpty()) {
			return;
		}
		if (tag.equals("genre")) {
			prog.addCategory(config.translateCategory(e.getTextContent()));
		} else if (tag.equals("eindtijd")) {
			prog.endTime = parseTime(e.getTextContent(), dateStatus, DateStatus.END_TIME);
		} else if (tag.equals("omroep")) {
		} else if (tag.equals("kijkwijzer")) {
			//System.out.println("Kijkwijzer: \"" + e.getTextContent() + "\"");
		} else if (tag.equals("presentatie")) {
			// A; A en B; A, B, C en D
			String[] presentatoren = e.getTextContent().split(", | en ");
			for(String pres:presentatoren) {
				prog.addPresenter(pres);
			}
		} else if (tag.equals("wwwadres")) {
			prog.addUrl(e.getTextContent());
		} else if (tag.equals("alginhoud")) {
			descStatus.alginhoud = e.getTextContent();
		} else if (tag.equals("inhoud")) {
			descStatus.inhoud = e.getTextContent();
		} else if (tag.equals("tt_inhoud")) {
			descStatus.tt_inhoud = e.getTextContent();
			// ignore, is summary of other fields
		} else if (tag.equals("zendernr")) {
		} else if (tag.equals("titel")) {
		} else if (tag.equals("bijvnwlanden")) {
		} else if (tag.equals("afl_titel")) {
			prog.addSecondaryTitle(e.getTextContent());
		} else if (tag.equals("site_path")) {
		} else if (tag.equals("ondertiteling")) {
			if(e.getTextContent().equals("J")) {
				prog.addSubtitle("teletext");
			} else {
				throw new RTLException("Ignoring unknown value \"" + n.getTextContent() + "\" for tag ondertiteling");
			}
		} else if (tag.equals("begintijd")) {
		} else if (tag.equals("pgmsoort")) {
		} else {
			throw new RTLException("Ignoring unknown tag " + n.getNodeName() + ", content: \"" + e.getTextContent() + "\"");
		}
		//prog.endTime = parseTime(date, root.)
	}

	@Override
	public List<Programme> getProgrammes(List<Channel> channels, int day) throws Exception {
		List<Programme> result = new LinkedList<Programme>();
		Map<String,Channel> channelMap = new HashMap<String,Channel>();
		for(Channel c: channels) {
			if (c.enabled && c.source==getId()) channelMap.put(c.id, c);
		}
		URL url = programmeUrl(day);
		//String xmltext = fetchURL(url);
		//System.out.println(xmltext);
		Thread.sleep(config.niceMilliseconds);
		Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.openStream());
		Element root = xml.getDocumentElement();
		Date date = new SimpleDateFormat("yyyy-MM-dd").parse(root.getAttribute("date"));
		DateStatus dateStatus = new DateStatus(date);
		//System.out.println("date: " + date);
		String json = root.getTextContent();
		//System.out.println("json: " + json);
		JSONObject o = JSONObject.fromObject( json );
		String prevChannel = null;
		for( Object k: o.keySet()) {
			String id = genericChannelId(k.toString());
			if(!channelMap.containsKey(id)) {
				//if (!config.quiet) System.out.println("Skipping programmes for channel " + id);
				continue;
			}
			if (!id.equals(prevChannel)) {
				dateStatus.reset(date);
				prevChannel = id;
			}
			JSONArray j = (JSONArray) o.get(k);
			//System.out.println(k.toString()+": "+j.toString());
			//System.out.println("Channel name:" + j.get(0));
			for (int i=1; i<j.size() && i<MAX_PROGRAMMES_PER_DAY; i++) {
				JSONArray p = (JSONArray) j.get(i);
				String starttime = p.getString(0);
				String title = p.getString(1);
				String programme_id = p.getString(2);
				String genre_id = p.getString(3); // 1 = amusement, etc
				String quark2 = p.getString(4); // 0 of 1, movie flag?
				if(debug) debugWriter.print("\""+id+"\",\""+starttime+"\",\""+title+"\",\""+genre_id+"\",\""+quark2+"\",");
				Programme prog = cache.get(getId(), programme_id);
				if (prog == null) {
					stats.cacheMisses++;
					prog = new Programme();
					prog.addTitle(title);
					prog.startTime = parseTime(starttime, dateStatus, DateStatus.START_TIME);
					prog.channel = channelMap.get(id).getXmltvChannelId();
					if (config.fetchDetails) {
						fetchDetail(prog, dateStatus, programme_id);
					}
					cache.put(getId(), programme_id, prog);
				} else {
					stats.cacheHits++;
				}
				result.add(prog);
			}
		}
		return result;
	}

	// Assumption: programmes are more-or-less in ascending order by start time
	private Date parseTime(String time, DateStatus status, int mode) {
		Calendar result = Calendar.getInstance();
		result.setTime(status.programDate);
		String[] parts = time.split(":");
		if(parts.length != 2) {
			if (!config.quiet)System.out.println("Wrong time format " + time); 
			// ignore
		}
		result.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
		result.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
		Calendar prev = status.prevStartTime;
		// Check if the start time of a new program is at most one hour before the start time of 
		// the previous one. End time of a program should be at or after the start time of the 
		// program. Else, assume it's on the next day.
		if (prev != null) {
			if (mode == DateStatus.START_TIME){ 
				prev.add(Calendar.HOUR_OF_DAY, -1);
			}
			if (result.before(prev)) {
				result.add(Calendar.DAY_OF_MONTH, 1); 
			}
		}
		if (mode==DateStatus.START_TIME) {
			status.prevStartTime = result;
		}
		return result.getTime();
	}

	private static URL programmeUrl(int day) throws MalformedURLException {
		return new URL(programme_url+day);
	}
	
	private static URL detailUrl(String id) throws Exception {
		return new URL(detail_url+id);
	}

	/**
	 * @param args
	 * @throws FileNotFoundException 
	 */
	public static void main(String[] args) throws FileNotFoundException {
		/*
		Calendar result = Calendar.getInstance();
		Calendar d = Calendar.getInstance();
		try {
			d.setTime(new SimpleDateFormat("yyyy-MM-dd").parse("2012-04-16"));
		} catch (ParseException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}

		SimpleDateFormat df = new SimpleDateFormat("HH:mm");
		try {
			result.setTime(df.parse("04:50"));
			result.set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH));
			System.out.println(result.getTime());
			System.exit(1);
		} catch (ParseException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		*/
		debug = true;
		Config config = Config.getDefaultConfig();
		config.niceMilliseconds = 50;
		RTL rtl = new RTL(2, config);
		if (debug) {
			rtl.cache.clear();
			System.out.println("Writing CSV to rtl.csv");
			rtl.debugWriter = new PrintWriter( new BufferedOutputStream(new FileOutputStream("rtl.csv")));
			rtl.debugWriter.print("\"zender\",\"starttime\",\"title\",\"quark1\",\"quark2\",");
			for(int k=0; k<rtl.xmlKeys.length; k++) {
				rtl.debugWriter.print(rtl.xmlKeys[k]);
				rtl.debugWriter.print(",");
			}
			rtl.debugWriter.println();
		}

		try {
			List<Channel> channels = rtl.getChannels();
			System.out.println("Channels: " + channels);
			XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(new FileWriter("rtl.xml"));
			writer.writeStartDocument();
			writer.writeCharacters("\n");
			writer.writeDTD("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">");
			writer.writeCharacters("\n");
			writer.writeStartElement("tv");
			for(Channel c: channels) {c.serialize(writer);}
			writer.flush();
			//List<Programme> programmes = rtl.getProgrammes(channels.subList(6, 9), 0);
			for(int day=0; day<10; day++) {
				List<Programme> programmes = rtl.getProgrammes(channels, day);
				for(Programme p: programmes) {p.serialize(writer);}
			}
			writer.writeEndElement();
			writer.writeEndDocument();
			writer.flush();
			if (!config.quiet) {
				EPGSource.Stats stats = rtl.getStats();
				System.out.println("Number of programmes from cache: " + stats.cacheHits);
				System.out.println("Number of programmes fetched: " + stats.cacheMisses);
				System.out.println("Number of fetch errors: " + stats.fetchErrors);
			}
			if (debug) {
				rtl.debugWriter.flush();
				rtl.debugWriter.close();
			}
			rtl.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
