package quotail;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.*;

// dxfeed imports
import com.devexperts.util.TimeFormat;
import com.dxfeed.api.*;
import com.dxfeed.event.EventType;
import com.dxfeed.event.TimeSeriesEvent;
import com.dxfeed.event.market.TimeAndSale;
import com.dxfeed.api.osub.WildcardSymbol;
import com.devexperts.qd.qtp.QDEndpoint;

import org.apache.commons.cli.*;

//kafka imports
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

public class DXFeedStreamAll{
	static Producer<byte[], byte[]> producer;
	static int counter;
	static Mode mode;
	public enum Mode{
		REALTIME, TIMESERIES, FILE
	}
	public static boolean updateTradeTime = false;
	static final String TOPIC_NAME = "timeandsales4";
	static PrintWriter tradeOut = null;
	static PrintWriter canceledOut = null;
	public static void main(String[] args){
		// config block for kafka producer
		// http://kafka.apache.org/documentation.html#topic-configs
		// Read section 3.3 Producer Configs
		Properties props = new Properties();
		props.put("metadata.broker.list", "localhost:9092");
		// we have the option of overriding the default serializer here, could this be used to remove the casting that the consumer has to do?
		props.put("serializer.class", "kafka.serializer.DefaultEncoder");
		props.put("partitioner.class", "quotail.TickerPartitioner");
		props.put("request.required.acks", "1");
		ProducerConfig config = new ProducerConfig(props);
		producer = new Producer<byte[], byte[]>(config);
		processOptions(args);
	}
	
	static void processOptions(String[] args){
		Options options = new Options();
		Option filename = OptionBuilder.withArgName("file").hasArg()
				.withDescription("the file path from which to read trades")
				.create("file");
		Option outfile = OptionBuilder.withArgName("outfile").hasArg()
				.withDescription("the file path to which trades are written")
				.create("outfile");
		Option contracts = OptionBuilder.withArgName("contracts").hasArg()
				.withDescription("comma separated list of contracts to subscribe to when in time series mode")
				.create("contracts");
		Option fromtime = OptionBuilder.withArgName("fromtime").hasArg()
				.withDescription("UNIX timestamp from which to read timeseries options").create("fromtime");
		options.addOption(filename);
		options.addOption(outfile);
		options.addOption("stdin", false, "read in the list of trades from standard input");
		options.addOption("batch", false, "read the trades from a file in batch mode");
		options.addOption("timeseries", false, "enable time series subscription to a set of contracts");
		options.addOption("use_current_time", false, "update the time stamp of the trades to the current time, useful for timing the latency");
		options.addOption(contracts);
		options.addOption(fromtime);
		options.addOption("realtime", false, "real-time subscription (default)");
		
		CommandLineParser parser = new BasicParser();
		try{
			canceledOut = new PrintWriter(new BufferedWriter(new FileWriter("correction_trades.log", true)));
			CommandLine cmd = parser.parse(options, args);

			if(cmd.hasOption("outfile")){
        		tradeOut = new PrintWriter(new BufferedWriter(new FileWriter(cmd.getOptionValue("outfile"), true)));
			}			
			if(cmd.hasOption("stdin")){
				Scanner scanner = new Scanner(System.in);
				new DXFeedStreamAll(scanner);
			}
			else if(cmd.hasOption("file")){
				updateTradeTime = cmd.hasOption("use_current_time");
				new DXFeedStreamAll(cmd.getOptionValue("file"), cmd.hasOption("batch"));
			}
			else if(cmd.hasOption("timeseries")){
				if(!cmd.hasOption("contracts") || !cmd.hasOption("fromtime")){
					System.out.println("must include 'contracts' and 'fromtime' args when in time series mode");
					System.exit(1);
				}
				new DXFeedStreamAll(cmd.getOptionValue("contracts").split(","), Long.parseLong(cmd.getOptionValue("fromtime")));
			}
			else{
				new DXFeedStreamAll();
			}
		}catch(ParseException e){
			System.out.println("error parsing arguments");
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("<-stdin> | <-f FILENAME -batch> | <-timeseries -contracts CONTRACTS -fromtime TIMESTAMP> | <--realtime> | <--use_current_time>", options);
			System.exit(1);
		} catch (IOException e) {
			System.out.println("Unable to open file for writing trades");
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	// listener for processing from standard in
	public DXFeedStreamAll(Scanner in){
		String line;
		TradeListener listener = new TradeListener();
		while((line = in.nextLine()) != "quit"){
			TimeAndSale t = DXFeedUtils.parseTrade(line);
			List<TimeAndSale> tns = new ArrayList<TimeAndSale>();
			tns.add(t);
			listener.processTrades(tns);
		}
	}
	
	// constructor for file based streaming
	public DXFeedStreamAll(String filename, boolean isBatch){
		try{
			TradeListener listener = new TradeListener();
			BufferedReader reader = new BufferedReader(new FileReader(filename));
			String nextLine;
			TimeAndSale t = DXFeedUtils.parseTrade(reader.readLine());
			while((nextLine = reader.readLine()) != null || t != null){
				if(t != null){
					List<TimeAndSale> tns = new ArrayList<TimeAndSale>();
					tns.add(t);
					listener.processTrades(tns);
					TimeAndSale nextTrade = DXFeedUtils.parseTrade(nextLine);
					if(!isBatch && nextTrade != null){
						// wait the appropriate amount of time in between iterations based on the timestamp
						long currentTime = System.currentTimeMillis();
						while(System.currentTimeMillis() - currentTime < nextTrade.getTime() - t.getTime());
					}
					t = nextTrade;
				}
				else{
					t = DXFeedUtils.parseTrade(nextLine);
				}
			}
			
			reader.close();
		}
		catch(FileNotFoundException e){
			System.out.println("Could not find " + filename);
		}
		catch(IOException e){
			e.printStackTrace();
		}
	}
	
	//constructor for time series based streaming
	public DXFeedStreamAll(String[] contracts, long fromTime){
		DXFeed feed = DXFeed.getInstance();
		// replay events using time series subscription
		DXFeedTimeSeriesSubscription<TimeAndSale> timeSeriesSub = feed.createTimeSeriesSubscription(TimeAndSale.class);
		timeSeriesSub.addEventListener(new TradeListener());
		timeSeriesSub.setFromTime(fromTime);
		timeSeriesSub.addSymbols(new ArrayList<String>(Arrays.asList(contracts)));
	}

	// constructor for real-time streaming
	public DXFeedStreamAll(){
		DXFeed feed = DXFeed.getInstance();
		DXFeedSubscription<TimeAndSale> sub = feed.createSubscription(TimeAndSale.class);
		sub.addEventListener(new TradeListener());
		sub.addSymbols(WildcardSymbol.ALL);
		while(true){
			// persist on the command line
		}
	}
	
	public class TradeListener implements DXFeedEventListener<TimeAndSale>{
		public void processTrades(List<TimeAndSale> events){
			List<KeyedMessage<byte[], byte[]>> trades = new ArrayList<KeyedMessage<byte[], byte[]>>();
			for (TimeAndSale event : events){
				event.setEventSymbol(DXFeedUtils.normalizeContract(event.getEventSymbol()));
				if(updateTradeTime){
					// set time of trade to current time for measuring latency purposes
					event.setTime(System.currentTimeMillis());
				}
				String ticker = DXFeedUtils.getTicker(event.getEventSymbol());
				if(tradeOut != null){
		    		tradeOut.println(DXFeedUtils.serializeTrade(event));
		    		//flush out trades and canceled file every so often
		    		if(counter % 200 == 0){
		    			tradeOut.flush();
		    			canceledOut.flush();
		    		}
				}
				if(event.isCancel() || event.isCorrection()){
					// canceled or correction event type
					canceledOut.println(event.toString());
				}
				// we have no interest in parsing trades that are not during normal market hours or are mini contracts
				if(!(updateTradeTime || DXFeedUtils.isDuringMarketHours(event.getTime())) || DXFeedUtils.isMiniContract(ticker))
					continue;
		        ByteArrayOutputStream b = new ByteArrayOutputStream();
				try{
			        ObjectOutputStream o = new ObjectOutputStream(b);
			        o.writeObject(event);
				}
				catch(IOException e){
					e.printStackTrace();
				}
				System.out.println(++counter + "\t" + event);
				trades.add(new KeyedMessage<byte[], byte[]>(TOPIC_NAME, ticker.getBytes(), b.toByteArray()));
			}
			producer.send(trades);
		}
		public void eventsReceived(List<TimeAndSale> events) {
			processTrades(events);
		}
	}
}
