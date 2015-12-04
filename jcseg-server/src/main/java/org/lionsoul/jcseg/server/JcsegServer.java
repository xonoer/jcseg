package org.lionsoul.jcseg.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.lionsoul.jcseg.json.JSONArray;
import org.lionsoul.jcseg.json.JSONObject;
import org.lionsoul.jcseg.server.controller.MainController;
import org.lionsoul.jcseg.server.controller.KeyphraseController;
import org.lionsoul.jcseg.server.controller.KeywordsController;
import org.lionsoul.jcseg.server.controller.SentenceController;
import org.lionsoul.jcseg.server.controller.SummaryController;
import org.lionsoul.jcseg.server.controller.TokenizerController;
import org.lionsoul.jcseg.server.core.AbstractRouter;
import org.lionsoul.jcseg.server.core.DynamicRestRouter;
import org.lionsoul.jcseg.server.core.ServerConfig;
import org.lionsoul.jcseg.server.core.StandardHandler;
import org.lionsoul.jcseg.tokenizer.core.ADictionary;
import org.lionsoul.jcseg.tokenizer.core.DictionaryFactory;
import org.lionsoul.jcseg.tokenizer.core.JcsegException;
import org.lionsoul.jcseg.tokenizer.core.JcsegTaskConfig;

/**
 * Jcseg RESTful api server
 * 
 * @author chenxin<chenxin619315@gmail.com>
*/
public class JcsegServer 
{
	/**
	 * jcseg server config 
	*/
	private ServerConfig config;
	
	/**
	 * jetty server instance 
	*/
	private Server server;
	
	/**
	 * global resource pool 
	*/
	private JcsegGlobalResource resourcePool = null;
	
	/**
	 * construct method
	 * 
	 * @param	config
	*/
	public JcsegServer(ServerConfig config)
	{
		this.config = config;
		resourcePool = new JcsegGlobalResource();
		init();
	}
	
	/**
	 * initialize the server and register the basic context handler
	*/
	private void init()
	{
		//setup thread pool
		QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setMaxThreads(config.getMaxThreadPoolSize());
		threadPool.setIdleTimeout(config.getThreadIdleTimeout());
		
		server = new Server(threadPool);
		
		//setup the http configuration
		HttpConfiguration http_config = new HttpConfiguration();
		http_config.setOutputBufferSize(config.getOutputBufferSize());
		http_config.setRequestHeaderSize(config.getRequestHeaderSize());
		http_config.setResponseHeaderSize(config.getRequestHeaderSize());
		http_config.setSendServerVersion(false);
		http_config.setSendDateHeader(false);
		
		//setup the connector
		ServerConnector connector = new ServerConnector(
			server, 
			new HttpConnectionFactory(http_config)
		);
		connector.setPort(config.getPort());
		connector.setHost(config.getHost());
		connector.setIdleTimeout(config.getHttpIdleTimeout());
		server.addConnector(connector);
	}
	
	/**
	 * register handler service 
	*/
	public JcsegServer registerHandler()
	{
		String basePath = this.getClass().getPackage().getName()+".controller";
		AbstractRouter router = new DynamicRestRouter(basePath, MainController.class);
		router.addMapping("/extractor/keywords", KeywordsController.class);
		router.addMapping("/extractor/keyphrase", KeyphraseController.class);
		router.addMapping("/extractor/sentence", SentenceController.class);
		router.addMapping("/extractor/summary", SummaryController.class);
		router.addMapping("/tokenizer/default", TokenizerController.class);
		
		/*
		 * the rest of path and dynamic rest checking will handler it 
		*/
		//router.addMapping("/tokenizer/default", TokenizerController.class);
		
		/*
		 * prepare standard handler
		*/
		StandardHandler stdHandler = new StandardHandler(config, resourcePool, router);
		
		/*
		 * prepare the resource handler 
		*/
		JcsegResourceHandler resourceHandler = new JcsegResourceHandler();
		
		/*
		 * i am going to rewrite the path to handler mapping mechanism
		 * check the Router handler for more info 
		*/
		GzipHandler gzipHandler = new GzipHandler();
		HandlerList handlers = new HandlerList();
		handlers.setHandlers(new Handler[]{stdHandler, resourceHandler});
		gzipHandler.setHandler(handlers);
		server.setHandler(gzipHandler);
		
		return this;
	}
	
	/**
	 * register global resource (global resource initialize)
	 * 
	 * @param	globalConfig
	 * @return JcsegServer
	 * @throws CloneNotSupportedException 
	 * @throws JcsegException 
	*/
	public JcsegServer registerGlobalResource(JSONObject globalConfig) 
			throws CloneNotSupportedException, JcsegException
	{

		//create a global JcsegTaskConfig and initialize from the global_setting
		JcsegTaskConfig globalJcsegTaskConfig = new JcsegTaskConfig(null, false);
		if ( globalConfig.has("global_config") ) {
			JSONObject globalSetting = globalConfig.getJSONObject("global_config");
			resetJcsegTaskConfig(globalJcsegTaskConfig, globalSetting);
		}
		
		/*
		 * create the dictionaris according to the defination of dict 
		 * and we will make a copy of the globalSetting for dictionary load
		 * 
		 * reset the max length to pass the dictionary words length limitation
		*/
		JcsegTaskConfig dictLoadConfig = globalJcsegTaskConfig.clone();
		dictLoadConfig.setMaxLength(100);
		if ( globalConfig.has("dict") )
		{
			JSONObject dictSetting = globalConfig.getJSONObject("dict");
			String[] dictNames = JSONObject.getNames(dictSetting);
			for ( String name : dictNames )
			{
				JSONObject dicJson = dictSetting.getJSONObject(name);
				if ( ! dicJson.has("path") ) {
					throw new JcsegException("Missing path for dict instance " + name);
				}
				
				JSONArray path = dicJson.getJSONArray("path");
				if ( path.length() == 0 ) {
					throw new JcsegException("Empty path setting for dict instance " + name);
				}
				
				//process the lexPath
				List<String> dicPath = new ArrayList<String>();
				for ( int i = 0; i < path.length(); i++ ) {
					dicPath.add(path.get(i).toString());
				}
				String[] lexPath = new String[dicPath.size()];
				dicPath.toArray(lexPath);
				dicPath.clear(); dicPath = null;
				
				boolean autoload = dicJson.has("autoload") 
						? dicJson.getBoolean("autoload") : false;
				int polltime = dicJson.has("polltime") 
						? dicJson.getInt("polltime") : 300;
				dictLoadConfig.setAutoload(autoload);
				dictLoadConfig.setPollTime(polltime);
				dictLoadConfig.setLexiconPath(lexPath);
				
				//create and register the global dictionary resource
				ADictionary dic = DictionaryFactory.createDefaultDictionary(dictLoadConfig);
				resourcePool.addDict(name, dic);
			}
		}
		
		dictLoadConfig = null;
		
		/*
		 * create the JcsegTaskConfig instance according to the defination config
		*/
		if ( globalConfig.has("config") ) 
		{
			JSONObject configSetting = globalConfig.getJSONObject("config");
			String[] configNames = JSONObject.getNames(configSetting);
			for ( String name : configNames ) 
			{
				JSONObject configJson = configSetting.getJSONObject(name);
				
				//clone the globalJcsegTaskConfig
				//and do the override working by local defination
				JcsegTaskConfig config = globalJcsegTaskConfig.clone();
				if ( configJson.length() > 0 ) {
					resetJcsegTaskConfig(config, configJson);
				}
				
				//register the global resource
				resourcePool.addConfig(name, config);
			}
		}
		
		/*
		 * create the tokenizer instance according the defination of tokenizer
		*/
		if ( globalConfig.has("tokenizer") )
		{
			JSONObject tokenizerSetting = globalConfig.getJSONObject("tokenizer");
			String[] tokenizerNames = JSONObject.getNames(tokenizerSetting);
			for ( String name : tokenizerNames )
			{
				JSONObject tokenizerJson = tokenizerSetting.getJSONObject(name);
				
				int algorithm = tokenizerJson.has("algorithm") 
						? tokenizerJson.getInt("algorithm") : JcsegTaskConfig.COMPLEX_MODE;
				
				if ( ! tokenizerJson.has("dict") ) {
					throw new JcsegException("Missing dict setting for tokenizer " + name);
				}
				if ( ! tokenizerJson.has("config") ) {
					throw new JcsegException("Missing config setting for tokenizer " + name);
				}
				
				ADictionary dic = resourcePool.getDict(tokenizerJson.getString("dict"));
				JcsegTaskConfig config = resourcePool.getConfig(tokenizerJson.getString("config"));
				if ( dic == null ) {
					throw new JcsegException("Unknow dict instance " 
						+ tokenizerJson.getString("dict") + " for tokenizer " + name);
				}
				
				if ( config == null ) {
					throw new JcsegException("Unknow config instance " 
						+ tokenizerJson.getString("config") + " for tokenizer " + name);
				}
				
				resourcePool.addTokenizerEntry(name, new JcsegTokenizerEntry(algorithm, config, dic));
			}
		}
		
		return this;
	}
	

	/**
	 * reset a JcsegTaskConfig from a JSONObject
	 * 
	 *  @param	config
	 *  @param	json
	*/
	private void resetJcsegTaskConfig(JcsegTaskConfig config, JSONObject json)
	{
		if ( json.has("jcseg_maxlen") ) {
			config.setMaxLength(json.getInt("jcseg_maxlen"));
		}
		if ( json.has("jcseg_icnname") ) {
			config.setICnName(json.getBoolean("jcseg_icnname"));
		}
		if ( json.has("jcseg_mixcnlen") ) {
			config.setMixCnLength(json.getInt("jcseg_mixcnlen"));
		}
		if ( json.has("jcseg_pptmaxlen") ) {
			config.setPPT_MAX_LENGTH(json.getInt("jcseg_pptmaxlen"));
		}
		if ( json.has("jcseg_cnmaxlnadron") ) {
			config.setMaxCnLnadron(json.getInt("jcseg_cnmaxlnadron"));
		}
		if ( json.has("jcseg_clearstopword") ) {
			config.setClearStopwords(json.getBoolean("jcseg_clearstopword"));
		}
		if ( json.has("jcseg_cnnumtoarabic") ) {
			config.setCnNumToArabic(json.getBoolean("jcseg_cnnumtoarabic"));
		}
		if ( json.has("jcseg_cnfratoarabic") ) {
			config.setCnFactionToArabic(json.getBoolean("jcseg_cnfratoarabic"));
		}
		if ( json.has("jcseg_keepunregword") ) {
			config.setKeepUnregWords(json.getBoolean("jcseg_keepunregword"));
		}
		if ( json.has("jcseg_ensencondseg") ) {
			config.setEnSecondSeg(json.getBoolean("jcseg_ensencondseg"));
		}
		if ( json.has("jcseg_stokenminlen") ) {
			config.setSTokenMinLen(json.getInt("jcseg_stokenminlen"));
		}
		if ( json.has("jcseg_nsthreshold") ) {
			config.setNameSingleThreshold(json.getInt("jcseg_nsthreshold"));
		}
		if ( json.has("jcseg_keeppunctuations") ) {
			config.setKeepPunctuations(json.getString("jcseg_keeppunctuations"));
		}
	}

	/**
	 * start the server 
	 * 
	 * @throws Exception 
	*/
	public void start() throws Exception
	{
		if ( server != null ) {
			server.start();
			server.join();
		}
	}
	
	/**
	 * stop the server 
	 * 
	 * @throws Exception 
	*/
	public void stop() throws Exception
	{
		if ( server != null ) {
			server.stop();
		}
	}

	public static void main(String[] args) 
	{
		try {
			JcsegServerConfig config = new JcsegServerConfig();
			config.resetFromFile("/data0/Code/java/JavaSE/jcseg/jcseg-server.properties");
			JcsegServer server = new JcsegServer(config);
			System.out.print("+--[Info]: Register handler ... ");
			server.registerHandler();
			System.out.println(" --[Ok]");
			System.out.print("+--[Info]: Register global resource ... ");
			server.registerGlobalResource(config.getGlobalConfig());
			System.out.println(" --[Ok]");
			server.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
