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
package com.ikanow.aleph2.management_db.services;

import java.sql.Timestamp; //(provides a short cut for some datetime manipulation that doesn't confusingly refer to timezones)
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;
import scala.concurrent.duration.FiniteDuration;

import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.ikanow.aleph2.core.shared.utils.DataServiceUtils;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IManagementCrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketStatusBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean.StateDirectoryType;
import com.ikanow.aleph2.data_model.objects.shared.AuthorizationBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.ProcessingTestSpecBean;
import com.ikanow.aleph2.data_model.objects.shared.ProjectBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.FutureUtils;
import com.ikanow.aleph2.data_model.utils.FutureUtils.ManagementFuture;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.management_db.controllers.actors.BucketActionSupervisor;
import com.ikanow.aleph2.management_db.controllers.actors.BucketDeletionActor;
import com.ikanow.aleph2.management_db.data_model.BucketActionMessage;
import com.ikanow.aleph2.management_db.data_model.BucketActionRetryMessage;
import com.ikanow.aleph2.management_db.data_model.BucketMgmtMessage.BucketDeletionMessage;
import com.ikanow.aleph2.management_db.data_model.BucketMgmtMessage.BucketTimeoutMessage;
import com.ikanow.aleph2.management_db.module.CoreManagementDbModule;
import com.ikanow.aleph2.management_db.utils.MgmtCrudUtils;
import com.ikanow.aleph2.management_db.utils.MgmtCrudUtils.SuccessfulNodeType;

import fj.Unit;

/** A layer that sits in between the managers and modules on top, and the actual database technology underneath,
 *  and performs control activities (launching into Akka) and an additional layer of validation
 * @author acp
 */
public class CoreManagementDbService implements IManagementDbService, IExtraDependencyLoader {
	private static final Logger _logger = LogManager.getLogger();
	
	protected final IServiceContext _service_context;
	protected final Provider<IManagementDbService> _underlying_management_db;	
	protected final DataBucketCrudService _data_bucket_service;
	protected final DataBucketStatusCrudService _data_bucket_status_service;
	protected final SharedLibraryCrudService _shared_library_service;
	protected final ManagementDbActorContext _actor_context;
	protected final Provider<ISecurityService> _security_service;
	
	protected final Optional<AuthorizationBean> _auth;
	protected final Optional<ProjectBean> _project;	
	
	private static final Long DEFAULT_MAX_STARTUP_TIME_SECS = 120L;
	
	protected class ReadOnly extends SetOnce<Boolean> {
		@Override
		public Boolean get() {
			return super.optional().orElseGet(() -> !_actor_context.getApplication().isPresent());
		}
	}	
	protected final ReadOnly _read_only = new ReadOnly();
	
	/** Guice invoked constructor
	 * @param underlying_management_db
	 * @param data_bucket_service
	 */
	@Inject
	public CoreManagementDbService(final IServiceContext service_context,
			final DataBucketCrudService data_bucket_service, final DataBucketStatusCrudService data_bucket_status_service,
			final SharedLibraryCrudService shared_library_service, final ManagementDbActorContext actor_context
			)
	{
		//(just return null here if underlying management not present, things will fail catastrophically unless this is a test)
		_service_context = service_context;
		_underlying_management_db = service_context.getServiceProvider(IManagementDbService.class, Optional.empty()).orElse(null);
		
		_data_bucket_service = data_bucket_service;
		_data_bucket_status_service = data_bucket_status_service;
		_shared_library_service = shared_library_service;
		_actor_context = actor_context;
		
		_auth = Optional.empty();
		_project = Optional.empty();

		// (leave _read_only alone, unless manually set will default to read-write for applications and read-only otherwise) 
		
		_security_service = service_context.getServiceProvider(ISecurityService.class, Optional.empty()).get();
		//DEBUG
		//System.out.println("Hello world from: " + this.getClass() + ": bucket=" + _data_bucket_service);
		//System.out.println("Hello world from: " + this.getClass() + ": underlying=" + _underlying_management_db);
	}
	
	/** User constructor for building a cloned version with different auth settings
	 * @param crud_factory 
	 * @param auth_fieldname
	 * @param auth
	 * @param project
	 */
	public CoreManagementDbService(final IServiceContext service_context,
			final Provider<IManagementDbService> underlying_management_db,
			final DataBucketCrudService data_bucket_service, final DataBucketStatusCrudService data_bucket_status_service,
			final SharedLibraryCrudService shared_library_service, final ManagementDbActorContext actor_context,		
			final Optional<AuthorizationBean> auth, final Optional<ProjectBean> project, boolean read_only) {
		_service_context = service_context;
		_underlying_management_db = underlying_management_db;
		_data_bucket_service = data_bucket_service;
		_data_bucket_status_service = data_bucket_status_service;
		_shared_library_service = shared_library_service;
		_actor_context = actor_context;
		
		_auth = auth;
		_project = project;		
		
		_read_only.set(read_only);
		_security_service = service_context.getServiceProvider(ISecurityService.class, Optional.empty()).get();
	}
	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getFilteredDb(java.lang.String, java.util.Optional, java.util.Optional)
	 */
	public IManagementDbService getFilteredDb(final Optional<AuthorizationBean> client_auth, final Optional<ProjectBean> project_auth)
	{
		return new CoreManagementDbService(_service_context, _underlying_management_db, 
				_data_bucket_service, _data_bucket_status_service, _shared_library_service, _actor_context,
				client_auth, project_auth, _read_only.get());
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getSecureddDb(java.lang.String, java.util.Optional, java.util.Optional)
	 */
	public IManagementDbService getSecuredDb(AuthorizationBean client_auth)
	{
		return new SecuredCoreManagementDbService(_service_context, _underlying_management_db.get(), 
				_data_bucket_service, _data_bucket_status_service, _shared_library_service, _actor_context ,client_auth);
		
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getSharedLibraryStore()
	 */
	public IManagementCrudService<SharedLibraryBean> getSharedLibraryStore() {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());
		return _shared_library_service.readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getPerLibraryState(java.lang.Class, com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean, java.util.Optional)
	 */
	public <T> ICrudService<T> getPerLibraryState(Class<T> clazz,
			SharedLibraryBean library, Optional<String> sub_collection) {		
		return _underlying_management_db.get().getPerLibraryState(clazz, library, sub_collection).readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getDataBucketStore()
	 */
	public IManagementCrudService<DataBucketBean> getDataBucketStore() {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());
		return _data_bucket_service.readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getDataBucketStatusStore()
	 */
	public IManagementCrudService<DataBucketStatusBean> getDataBucketStatusStore() {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());
		return _data_bucket_status_service.readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getPerBucketState(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional)
	 */
	public <T> ICrudService<T> getBucketHarvestState(Class<T> clazz,
			DataBucketBean bucket, Optional<String> sub_collection) {
		// (note: don't need to join the akka cluster in order to use the state objects)
		return _underlying_management_db.get().getBucketHarvestState(clazz, bucket, sub_collection).readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getPerBucketState(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional)
	 */
	public <T> ICrudService<T> getBucketEnrichmentState(Class<T> clazz,
			DataBucketBean bucket, Optional<String> sub_collection) {
		// (note: don't need to join the akka cluster in order to use the state objects)
		return _underlying_management_db.get().getBucketEnrichmentState(clazz, bucket, sub_collection).readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getPerAnalyticThreadState(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean, java.util.Optional)
	 */
	public <T> ICrudService<T> getBucketAnalyticThreadState(Class<T> clazz,
			DataBucketBean bucket, Optional<String> sub_collection) {
		// (note: don't need to join the akka cluster in order to use the state objects)
		return _underlying_management_db.get().getBucketAnalyticThreadState(clazz, bucket, sub_collection).readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	public <T> Optional<T> getUnderlyingPlatformDriver(Class<T> driver_class,
			Optional<String> driver_options) {
		throw new RuntimeException("No underlying drivers for CoreManagementDbService - did you want to get the underlying IManagementDbService? Use IServiceContext.getService(IManagementDbService.class, ...) if so.");
	}

	/** This service needs to load some additional classes via Guice. Here's the module that defines the bindings
	 * @return
	 */
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)new CoreManagementDbModule());
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader#youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules()
	 */
	@Override
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		// (done see above)		
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getRetryStore(java.lang.Class)
	 */
	@Override
	public <T> ICrudService<T> getRetryStore(
			Class<T> retry_message_clazz) {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());		
		return _underlying_management_db.get().getRetryStore(retry_message_clazz).readOnlyVersion(_read_only.get());
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getBucketDeletionQueue(java.lang.Class)
	 */
	@Override
	public <T> ICrudService<T> getBucketDeletionQueue(
			Class<T> deletion_queue_clazz) {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());
		
		return _underlying_management_db.get().getBucketDeletionQueue(deletion_queue_clazz).readOnlyVersion(_read_only.get());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getStateDirectory(java.util.Optional)
	 */
	@Override
	public ICrudService<AssetStateDirectoryBean> getStateDirectory(
			Optional<DataBucketBean> bucket_filter, Optional<StateDirectoryType> type_filter) {
		// (note: don't need to join the akka cluster in order to use the state objects)
		return _underlying_management_db.get().getStateDirectory(bucket_filter, type_filter).readOnlyVersion(_read_only.get());
	}
	

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		final LinkedList<Object> ll = new LinkedList<Object>();
		ll.add(this);
		ll.addAll(_underlying_management_db.get().getUnderlyingArtefacts());
		return ll;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#readOnlyVersion()
	 */
	@Override
	public IManagementDbService readOnlyVersion() {
		return new CoreManagementDbService(_service_context, _underlying_management_db, 
				_data_bucket_service, _data_bucket_status_service, _shared_library_service, _actor_context,
				_auth, _project, true);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#purgeBucket(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional)
	 */
	@Override
	public ManagementFuture<Boolean> purgeBucket(DataBucketBean to_purge, Optional<Duration> in) {
		if (!_read_only.get())
			ManagementDbActorContext.get().getDistributedServices().waitForAkkaJoin(Optional.empty());
		
		if (in.isPresent()) { // perform scheduled purge
			
			final Date to_purge_date = Timestamp.from(Instant.now().plus(in.get().getSeconds(), ChronoUnit.SECONDS));			
			
			return FutureUtils.createManagementFuture(this.getBucketDeletionQueue(BucketDeletionMessage.class).storeObject(new BucketDeletionMessage(to_purge, to_purge_date, true), false)					
					.thenApply(__ -> true)
					.exceptionally(___ -> false)) // (fail if already present)
					; 
		}
		else { // purge now...
		
			final CompletableFuture<Collection<BasicMessageBean>> sys_res = BucketDeletionActor.deleteAllDataStoresForBucket(to_purge, _service_context, false);
			
			final CompletableFuture<Collection<BasicMessageBean>> user_res = BucketDeletionActor.notifyHarvesterOfPurge(to_purge, this.getDataBucketStatusStore(), this.getRetryStore(BucketActionRetryMessage.class));
			//(longer term I wonder if should allow the harvester reply to dictate the level of deletion, eg could return an _id and then only delete up to that id?)

			final CompletableFuture<Collection<BasicMessageBean>> combined_res = sys_res.thenCombine(user_res, (a, b) -> {
				return Stream.concat(a.stream(), b.stream()).collect(Collectors.toList());
			});
			
			return FutureUtils.createManagementFuture(
					combined_res.thenApply(msgs -> msgs.stream().allMatch(m -> m.success()))
					, 
					combined_res);
		}
	}

	/**
	 * Performs a test run for a bucket.  A test run is a processing cycle that only runs as
	 * long as test_spec specifies and/or to the amount of results that test_spec specifies, whichever
	 * occurs first.
	 * 
	 * Changes a buckets name to not overwrite an existing job, then sends out a start test message.
	 * If that message is picked up, this throws an object on both the test queue and delete queue that
	 * will timeout the test and delete the results respectively after a certain amount of time.
	 * 
	 */
	@Override
	public ManagementFuture<Boolean> testBucket(DataBucketBean to_test, ProcessingTestSpecBean test_spec) {		
		//create a test bucket to put data into instead of the specified bucket
		final DataBucketBean test_bucket = BucketUtils.convertDataBucketBeanToTest(to_test, to_test.owner_id());		
		// - validate the bucket
		final Tuple2<DataBucketBean, Collection<BasicMessageBean>> validation = this._data_bucket_service.validateBucket(test_bucket, true);
		if (validation._2().stream().anyMatch(m -> !m.success())) {
			return FutureUtils.createManagementFuture(CompletableFuture.completedFuture(false), CompletableFuture.completedFuture(validation._2()));
		}
		DataBucketBean validated_test_bucket = validation._1();
		
		// Create full set of file paths for the test bucket
		try {
			DataBucketCrudService.createFilePaths(test_bucket, this._service_context.getStorageService());
		}
		catch (Exception e) {
			//return error
			_logger.error("Error creating file paths", e);
			return FutureUtils.createManagementFuture(CompletableFuture.completedFuture(false), 
					CompletableFuture.completedFuture(Arrays.asList(ErrorUtils.buildErrorMessage("CoreManagementDbService", "testBucket", "Error launching job: {0}", e.getMessage()))));
		}
				
		// - is there any test data already present for this user, delete if so (?)
		final CompletableFuture<BasicMessageBean> base_future = Lambdas.get(() -> {
			if ( Optional.ofNullable(test_spec.overwrite_existing_data()).orElse(true) ) {
				return purgeBucket(validated_test_bucket, Optional.empty()).exceptionally( t -> {
					_logger.error("Error clearing output datastore, probably okay: " + "ingest."+validated_test_bucket._id(), t);
					return false;
				});
			} else {
				return CompletableFuture.completedFuture(Unit.unit());
			}
		}).thenCompose(__ -> {
			// Register the bucket with its services before launching the test:
			final  Multimap<IDataServiceProvider, String> data_service_info = DataServiceUtils.selectDataServices(validated_test_bucket.data_schema(), _service_context);
			
			final List<CompletableFuture<Collection<BasicMessageBean>>> ds_update_results = data_service_info.asMap().entrySet().stream()
				.map(kv -> 
						kv.getKey()
							.onPublishOrUpdate(validated_test_bucket, Optional.empty(), false, 
								kv.getValue().stream().collect(Collectors.toSet()), 
								Collections.emptySet()
								)
				)
				.collect(Collectors.toList())
				;
			
			return CompletableFuture.allOf(ds_update_results.stream().toArray(CompletableFuture[]::new));
			
		}).thenCompose(__ -> {
									
			final long max_startup_time_secs = Optional.ofNullable(test_spec.max_startup_time_secs()).orElse(DEFAULT_MAX_STARTUP_TIME_SECS);
			final CompletableFuture<Collection<BasicMessageBean>> future_replies = 
					BucketActionSupervisor.askBucketActionActor(Optional.empty(),
								_actor_context.getBucketActionSupervisor(), 
								_actor_context.getActorSystem(), 
								new BucketActionMessage.TestBucketActionMessage(validated_test_bucket, test_spec), 
								Optional.of(FiniteDuration.create(max_startup_time_secs, TimeUnit.SECONDS))
							)
							.thenApply(msg -> msg.replies());
					
			return MgmtCrudUtils.getSuccessfulNodes(future_replies, SuccessfulNodeType.all_technologies)
					.thenCombine(future_replies, (hostnames, replies) -> {
						final String reply_str = replies.stream().map(m -> m.message()).collect(Collectors.joining(";"));
						//make sure there is at least 1 hostname result, otherwise throw error
						if ( !hostnames.isEmpty() ) {					
							// - add to the test queue
							ICrudService<BucketTimeoutMessage> test_service = getBucketTestQueue(BucketTimeoutMessage.class);
							final long max_run_time_secs = Optional.ofNullable(test_spec.max_run_time_secs()).orElse(60L);
							test_service.storeObject(new BucketTimeoutMessage(validated_test_bucket, 
									new Date(System.currentTimeMillis()+(max_run_time_secs*1000L)), 
									hostnames), true);
							
							// - add to the delete queue
							final ICrudService<BucketDeletionMessage> delete_queue = getBucketDeletionQueue(BucketDeletionMessage.class);
							final long max_storage_time_sec = Optional.ofNullable(test_spec.max_storage_time_secs()).orElse(86400L);
							delete_queue.storeObject(new BucketDeletionMessage(validated_test_bucket, new Date(System.currentTimeMillis()+(max_storage_time_sec*1000)), false), true);
							
							_logger.debug("Got hostnames successfully, added test to test queue and delete queue");
							return ErrorUtils.buildSuccessMessage("CoreManagementDbService", "testBucket", "Created test on hosts {0}, added test to test queue and delete queue\nmessages = {1}", hostnames.stream().collect(Collectors.joining(";")), reply_str);
						} else {
							final String err = ErrorUtils.get("Error, no successful actions performed\nmessages = {0}", reply_str);
							_logger.error(err);
							return ErrorUtils.buildErrorMessage("CoreManagementDbService", "testBucket", err);
						}					
					})
					.exceptionally(t -> {
						//return error
						_logger.error("Error getting hostnames", t);
						return ErrorUtils.buildErrorMessage("CoreManagementDbService", "testBucket", "Error launching job: {0}", t.getMessage());
					});
		})
		;
		return FutureUtils.createManagementFuture(
				base_future.<Boolean>thenApply(m -> m.success()) // (did it work?)
				,
				base_future.thenApply(m -> Arrays.asList(m))) // (what were the errors?)
				;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getBucketTestQueue(java.lang.Class)
	 */
	@Override
	public <T> ICrudService<T> getBucketTestQueue(Class<T> test_queue_clazz) {
		return _underlying_management_db.get().getBucketTestQueue(test_queue_clazz);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService#getAnalyticBucketTriggerState(java.lang.Class)
	 */
	@Override
	public <T> ICrudService<T> getAnalyticBucketTriggerState(
			Class<T> trigger_state_clazz) {
		return _underlying_management_db.get().getAnalyticBucketTriggerState(trigger_state_clazz);
	}
}
