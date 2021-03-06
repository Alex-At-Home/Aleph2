/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.ikanow.aleph2.data_import_manager.batch_enrichment.actors;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.actor.UntypedActor;

import com.ikanow.aleph2.data_import_manager.services.DataImportActorContext;
import com.ikanow.aleph2.core.shared.utils.DirUtils;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.ikanow.aleph2.management_db.utils.ActorUtils;

/** THIS CODE IS GETTING MOVED INTO THE DATA ANALYTICS MANAGER TRIGGER LOGIC
 * @author jfreydank
 */
public class FolderWatcherActor extends UntypedActor {
    private static final Logger logger = LogManager.getLogger(FolderWatcherActor.class);

	protected CuratorFramework _curator	;
	protected final DataImportActorContext _context;
	protected final IManagementDbService _management_db;
	protected final ICoreDistributedServices _core_distributed_services;
	protected final IStorageService _storage_service;
	protected GlobalPropertiesBean _global_properties_Bean = null; 
	protected List<Path> _bucket_paths = null;
	protected FileContext fileContext = null;
	protected Path dataPath = null;

	public static String MSG_START = "start";
	public static String MSG_STOP = "stop";
	public static String MSG_FOLDER_WATCH = "folderWatch";
	
    public FolderWatcherActor(IStorageService storage_service){
    	this._context = DataImportActorContext.get();
    	this._global_properties_Bean = _context.getGlobalProperties();
    	logger.debug("_global_properties_Bean"+_global_properties_Bean);
    	this._core_distributed_services = _context.getDistributedServices();    	
    	this._curator = _core_distributed_services.getCuratorFramework();
    	this._management_db = _context.getServiceContext().getCoreManagementDbService();
    	this._storage_service = storage_service;
		this.fileContext = storage_service.getUnderlyingPlatformDriver(FileContext.class,Optional.of("hdfs://localhost:8020")).get();
		this.dataPath = new Path(_global_properties_Bean.distributed_root_dir()+"/data");
		this._bucket_paths = detectBucketPaths();
    }
    
	
    private  Cancellable folderWatch = null;
    
	@Override
	public void postStop() {
		logger.debug("postStop");
		if(folderWatch!=null){
			folderWatch.cancel();
		}
	}

	@Override
	public void onReceive(Object message) throws Exception {
		if (MSG_START.equals(message)) {
			logger.debug("Start message received");
			folderWatch = getContext()
			.system()
			.scheduler()
			.schedule(Duration.create(1000, TimeUnit.MILLISECONDS),
					Duration.create(8000, TimeUnit.MILLISECONDS), getSelf(),
					MSG_FOLDER_WATCH, getContext().dispatcher(), null);				

		}else
		if (MSG_FOLDER_WATCH.equals(message)) {
			logger.debug("watchFolders message received");
			traverseFolders();
		}else 	if (MSG_STOP.equals(message)) {
				logger.debug("Stop message received");
				if(folderWatch!=null){
					folderWatch.cancel();
				}	
			} else {
				logger.debug("unhandeld message:"+message);
			unhandled(message);
		}
	}

	protected void traverseFolders() {
		logger.debug("traverseFolders for path: "+_bucket_paths);
		try {
			detectBucketPaths();
			if(_bucket_paths!=null){
				logger.debug("traverseFolders for path: "+_bucket_paths);
				for (Path path : _bucket_paths) {
					String bucketPathStr = path.toString();
					String dataPathStr = dataPath.toString();
				    String bucketFullName = createFullName(bucketPathStr, dataPathStr);
				    // create or send message to BatchBucketActors
				    checkAndScheduleBucketAgent(bucketPathStr, bucketFullName);					
				}
			}
		} catch (Exception e) {
			logger.error("traverseFolders Caught Exception:",e);
		}

	}
	
	protected static String createFullName(String bucketPathStr, String dataPathStr) {
		String fullName = bucketPathStr;
		int dataPathPos = bucketPathStr.indexOf(dataPathStr);
		if(dataPathPos>0 && bucketPathStr.length()> dataPathPos + dataPathStr.length()){
			fullName = bucketPathStr.substring(dataPathPos + dataPathStr.length());
		}
		return fullName;
	}

	protected static String createAgentName(String fullName) {
		String agentName = fullName.replace('/', '@').replace('\\', '@')+  UUID.randomUUID().toString();
		return agentName;
		
	}

	protected void checkAndScheduleBucketAgent(String bucketPathStr, String bucketFullName) {
		//curator_framework.
	    logger.debug("checkAndScheduleBucketAgent for Bucket Path: "+bucketPathStr+" ,Bucket id: "+bucketFullName);
		try{
			    String agentName = createAgentName(bucketFullName);
				ActorRef beActor = getContext().actorOf(Props.create(BeBucketActor.class,_storage_service),agentName);
				String bucketZkPath = ActorUtils.BATCH_ENRICHMENT_ZOOKEEPER + bucketFullName;
				beActor.tell(new BucketEnrichmentMessage(bucketPathStr, bucketFullName, bucketZkPath), getSelf());						
		}
		catch(Exception e){
			logger.error("Caught Exception",e);
		}
	}

	protected List<Path> detectBucketPaths(){
		logger.debug("detectBucketPaths");
		if(_bucket_paths==null){
			try {
				// looking for managed_bucket underneath /app/aleph2/data
				_bucket_paths =  new ArrayList<Path>();
				DirUtils.findAllSubdirectories(_bucket_paths, fileContext, dataPath, "managed_bucket",false);
				logger.info("detectBucketPath found "+_bucket_paths.size()+" folders.");
			} catch (Exception e) {
				logger.error("detectBucketPath Caught Exception",e);
			} 
		}
		return _bucket_paths;
	}
}
