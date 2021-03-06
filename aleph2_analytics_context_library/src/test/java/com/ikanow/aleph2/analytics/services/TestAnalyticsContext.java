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
package com.ikanow.aleph2.analytics.services;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;

import scala.Tuple2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.analytics.services.AnalyticsContext.State;
import com.ikanow.aleph2.analytics.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsAccessContext;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ILoggingService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.MockSecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.MockServiceContext;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean;
import com.ikanow.aleph2.data_model.objects.data_import.AnnotationBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean.MasterEnrichmentType;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.data_model.utils.ContextUtils;
import com.ikanow.aleph2.data_model.utils.CrudUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.ikanow.aleph2.distributed_services.utils.KafkaUtils;
import com.ikanow.aleph2.distributed_services.utils.WrappedConsumerIterator;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import fj.data.Either;
import fj.data.Validation;

public class TestAnalyticsContext {

	static final Logger _logger = LogManager.getLogger(); 

	protected ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	protected Injector _app_injector;
	
	@Inject
	protected IServiceContext _service_context;
	
	@Before
	public void injectModules() throws Exception {
		_logger.info("run injectModules");
		
		final Config config = ConfigFactory.parseFile(new File("./example_config_files/context_local_test.properties"));
		
		try {
			_app_injector = ModuleUtils.createTestInjector(Arrays.asList(), Optional.of(config));
			_app_injector.injectMembers(this);
		}
		catch (Exception e) {
			try {
				e.printStackTrace();
			}
			catch (Exception ee) {
				System.out.println(ErrorUtils.getLongForm("{0}", e));
			}
		}
	}
	
	@Test
	public void test_basicContextCreation() {
		_logger.info("run test_basicContextCreation");
		try {
			assertTrue("Injector created", _app_injector != null);
		
			final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
			
			assertTrue("AnalyticsContext created", test_context != null);
			
			assertTrue("AnalyticsContext dependencies", test_context._core_management_db != null);
			assertTrue("AnalyticsContext dependencies", test_context._distributed_services != null);
			assertTrue("AnalyticsContext dependencies", test_context._logging_service != null);
			assertTrue("AnalyticsContext dependencies", test_context._security_service != null);
			assertTrue("AnalyticsContext dependencies", test_context._storage_service != null);
			assertTrue("AnalyticsContext dependencies", test_context._globals != null);
			assertTrue("AnalyticsContext dependencies", test_context._service_context != null);
			
			assertTrue("Find service", test_context.getServiceContext().getService(ISearchIndexService.class, Optional.empty()).isPresent());
			
			// Check if started in "technology" (internal mode)
			assertEquals(test_context._state_name, AnalyticsContext.State.IN_TECHNOLOGY);
			
			// Check that multiple calls to create harvester result in different contexts but with the same injection:
			final AnalyticsContext test_context2 = _app_injector.getInstance(AnalyticsContext.class);
			assertTrue("AnalyticsContext created", test_context2 != null);
			assertTrue("AnalyticsContexts different", test_context2 != test_context);
			assertEquals(test_context._service_context, test_context2._service_context);
			
			// Check calls that require that bucket/endpoint be set
			final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::full_name, "/test/basicContextCreation")
					.with(DataBucketBean::modified, new Date())
					.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
							.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
									.done().get())
							.done().get())
					.done().get();
			
			final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::path_name, "/test/lib")
					.done().get();
			test_context.setTechnologyConfig(library);
			
			test_context.setBucket(test_bucket);
			assertEquals(test_bucket, test_context.getBucket().get());
			
			test_context2.setBucket(test_bucket);
			assertEquals(test_bucket, test_context2.getBucket().get());
		}
		catch (Exception e) {
			System.out.println(ErrorUtils.getLongForm("{1}: {0}", e, e.getClass()));
			throw e;
		}
	}
	
	@Test
	public void test_ExternalContextCreation() throws InstantiationException, IllegalAccessException, ClassNotFoundException, InterruptedException, ExecutionException {
		_logger.info("run test_ExternalContextCreation");
		try {
			assertTrue("Config contains application name: " + ModuleUtils.getStaticConfig().root().toString(), ModuleUtils.getStaticConfig().root().toString().contains("application_name"));
			assertTrue("Config contains v1_enabled: " + ModuleUtils.getStaticConfig().root().toString(), ModuleUtils.getStaticConfig().root().toString().contains("v1_enabled"));
			
			final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
	
			final AnalyticThreadJobBean test_job = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "test_job")
				.done().get();

			final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
													.with(DataBucketBean::_id, "test")
													.with(DataBucketBean::full_name, "/test/external-context/creation")
													.with(DataBucketBean::modified, new Date(1436194933000L))
													.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class) // (Example of a service that is only added if needed)
															.with("document_schema", BeanTemplateUtils.build(DataSchemaBean.DocumentSchemaBean.class)
																	.done().get())
															.done().get())
													.done().get();
			
			final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::path_name, "/test/lib")
					.done().get();

			test_context.setTechnologyConfig(library);			
			
			// Empty service set:
			final String signature = test_context.getAnalyticsContextSignature(Optional.of(test_bucket), Optional.empty());
						
			final String expected_sig = "com.ikanow.aleph2.analytics.services.AnalyticsContext:{\"3fdb4bfa-2024-11e5-b5f7-727283247c7e\":\"{\\\"_id\\\":\\\"test\\\",\\\"modified\\\":1436194933000,\\\"full_name\\\":\\\"/test/external-context/creation\\\",\\\"data_schema\\\":{\\\"document_schema\\\":{}}}\",\"3fdb4bfa-2024-11e5-b5f7-727283247c7f\":\"{\\\"path_name\\\":\\\"/test/lib\\\"}\",\"CoreDistributedServices\":{},\"MongoDbManagementDbService\":{\"mongodb_connection\":\"localhost:9999\"},\"globals\":{\"local_cached_jar_dir\":\"file://temp/\"},\"service\":{\"CoreDistributedServices\":{\"interface\":\"com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices\",\"service\":\"com.ikanow.aleph2.distributed_services.services.MockCoreDistributedServices\"},\"CoreManagementDbService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService\",\"service\":\"com.ikanow.aleph2.management_db.services.CoreManagementDbService\"},\"DocumentService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IDocumentService\",\"service\":\"com.ikanow.aleph2.search_service.elasticsearch.services.MockElasticsearchIndexService\"},\"LoggingService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.ILoggingService\",\"service\":\"com.ikanow.aleph2.logging.service.LoggingService\"},\"ManagementDbService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService\",\"service\":\"com.ikanow.aleph2.management_db.mongodb.services.MockMongoDbManagementDbService\"},\"SearchIndexService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService\",\"service\":\"com.ikanow.aleph2.search_service.elasticsearch.services.MockElasticsearchIndexService\"},\"SecurityService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService\",\"service\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.MockSecurityService\"},\"StorageService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService\",\"service\":\"com.ikanow.aleph2.storage_service_hdfs.services.MockHdfsStorageService\"}}}";			
			assertEquals(expected_sig, signature);

			final String signature_b = test_context.getAnalyticsContextSignature(Optional.of(test_bucket), Optional.empty()); // (running it again returns the cached var)
			assertEquals(expected_sig, signature_b);			
			
			// Check can't call multiple times
			
			// Additionals service set:

			try {
				test_context.getAnalyticsContextSignature(Optional.of(test_bucket),
												Optional.of(
														ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
															.add(Tuples._2T(IStorageService.class, Optional.empty()))
															.add(Tuples._2T(IManagementDbService.class, Optional.of("test")))
															.build()																
														)
												);
				fail("Should have errored");
			}
			catch (Exception e) {}
			// Create another injector:
			final AnalyticsContext test_context2 = _app_injector.getInstance(AnalyticsContext.class);
			test_context2.setTechnologyConfig(library);
			
			final SharedLibraryBean module_library = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::_id, "_test_module")
					.with(SharedLibraryBean::path_name, "/test/module")
					.done().get();
			test_context2.resetLibraryConfigs(
							ImmutableMap.<String, SharedLibraryBean>builder()
								.put(module_library.path_name(), module_library)
								.put(module_library._id(), module_library)
								.build()
					);
			test_context2.resetJob(test_job); //(note won't match against the bucket - will test that logic implicitly in test_objectEmitting)
			
			final String signature2 = test_context2.getAnalyticsContextSignature(Optional.of(test_bucket),
					Optional.of(
							ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
								.add(Tuples._2T(IStorageService.class, Optional.empty()))
								.add(Tuples._2T(IManagementDbService.class, Optional.of("test")))
								.build()																
							)
					);
			
			
			final String expected_sig2 = "com.ikanow.aleph2.analytics.services.AnalyticsContext:{\"3fdb4bfa-2024-11e5-b5f7-727283247c7e\":\"{\\\"_id\\\":\\\"test\\\",\\\"modified\\\":1436194933000,\\\"full_name\\\":\\\"/test/external-context/creation\\\",\\\"data_schema\\\":{\\\"document_schema\\\":{}}}\",\"3fdb4bfa-2024-11e5-b5f7-727283247c7f\":\"{\\\"path_name\\\":\\\"/test/lib\\\"}\",\"3fdb4bfa-2024-11e5-b5f7-727283247cff\":\"{\\\"libs\\\":[{\\\"_id\\\":\\\"_test_module\\\",\\\"path_name\\\":\\\"/test/module\\\"}]}\",\"3fdb4bfa-2024-11e5-b5f7-7272832480f0\":\"test_job\",\"CoreDistributedServices\":{},\"MongoDbManagementDbService\":{\"mongodb_connection\":\"localhost:9999\"},\"globals\":{\"local_cached_jar_dir\":\"file://temp/\"},\"service\":{\"CoreDistributedServices\":{\"interface\":\"com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices\",\"service\":\"com.ikanow.aleph2.distributed_services.services.MockCoreDistributedServices\"},\"CoreManagementDbService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService\",\"service\":\"com.ikanow.aleph2.management_db.services.CoreManagementDbService\"},\"DocumentService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IDocumentService\",\"service\":\"com.ikanow.aleph2.search_service.elasticsearch.services.MockElasticsearchIndexService\"},\"LoggingService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.ILoggingService\",\"service\":\"com.ikanow.aleph2.logging.service.LoggingService\"},\"ManagementDbService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService\",\"service\":\"com.ikanow.aleph2.management_db.mongodb.services.MockMongoDbManagementDbService\"},\"SearchIndexService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService\",\"service\":\"com.ikanow.aleph2.search_service.elasticsearch.services.MockElasticsearchIndexService\"},\"SecurityService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService\",\"service\":\"com.ikanow.aleph2.data_model.interfaces.shared_services.MockSecurityService\"},\"StorageService\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService\",\"service\":\"com.ikanow.aleph2.storage_service_hdfs.services.MockHdfsStorageService\"},\"test\":{\"interface\":\"com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService\",\"service\":\"com.ikanow.aleph2.management_db.mongodb.services.MockMongoDbManagementDbService\"}}}"; 
			assertEquals(expected_sig2, signature2);
			
			final IAnalyticsContext test_external1a = ContextUtils.getAnalyticsContext(signature);		
			
			assertTrue("external context non null", test_external1a != null);
			
			assertTrue("external context of correct type", test_external1a instanceof AnalyticsContext);
			
			final AnalyticsContext test_external1b = (AnalyticsContext)test_external1a;
			
			assertTrue("AnalyticsContext dependencies", test_external1b._core_management_db != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._logging_service != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._security_service != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._storage_service != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._distributed_services != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._globals != null);
			assertTrue("AnalyticsContext dependencies", test_external1b._service_context != null);
			
			assertEquals("test", test_external1b._mutable_state.bucket.get()._id());
			
			// Check that it gets cloned
			
			final IAnalyticsContext test_external1a_1 = ContextUtils.getAnalyticsContext(signature);		
			
			assertTrue("external context non null", test_external1a_1 != null);
			
			assertTrue("external context of correct type", test_external1a_1 instanceof AnalyticsContext);
			
			final AnalyticsContext test_external1b_1 = (AnalyticsContext)test_external1a_1;
			
			assertEquals(test_external1b_1._distributed_services, test_external1b._distributed_services);
			
			// Finally, check I can see my extended services: 
			
			final IAnalyticsContext test_external2a = ContextUtils.getAnalyticsContext(signature2);		
			
			assertTrue("external context non null", test_external2a != null);
			
			assertTrue("external context of correct type", test_external2a instanceof AnalyticsContext);
			
			final AnalyticsContext test_external2b = (AnalyticsContext)test_external2a;
			
			assertTrue("AnalyticsContext dependencies", test_external2b._core_management_db != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._logging_service != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._security_service != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._storage_service != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._distributed_services != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._globals != null);
			assertTrue("AnalyticsContext dependencies", test_external2b._service_context != null);
			
			assertEquals("test", test_external2b._mutable_state.bucket.get()._id());
			
			assertEquals("/test/lib", test_external2b._mutable_state.technology_config.get().path_name());			
			assertEquals("/test/lib", test_external2b.getTechnologyConfig().path_name());			
			
			assertTrue("I can see my additonal services", null != test_external2b._service_context.getService(IStorageService.class, Optional.empty()));
			assertTrue("I can see my additonal services", null != test_external2b._service_context.getService(IManagementDbService.class, Optional.of("test")));
						
			//Check some "won't work in module" calls:
			try {
				test_external2b.getAnalyticsContextSignature(null, null);
				fail("Should have errored");
			}
			catch (Exception e) {
				assertEquals(ErrorUtils.TECHNOLOGY_NOT_MODULE, e.getMessage());
			}
			try {
				test_external2b.getUnderlyingArtefacts();
				fail("Should have errored");
			}
			catch (Exception e) {
				assertEquals(ErrorUtils.TECHNOLOGY_NOT_MODULE, e.getMessage());
			}
			
			// Finally, test serialization
			{
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
				ObjectOutputStream out = new ObjectOutputStream(baos);
				out.writeObject(test_external2a);
				
			    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			    ObjectInputStream ois = new ObjectInputStream(bais);
			    Object o = ois.readObject();
			    
			    assertEquals(AnalyticsContext.class, o.getClass());
			    final AnalyticsContext test_external_serialized = (AnalyticsContext) test_external2a;
			    
			    assertTrue(null != test_external_serialized._mapper);
			    assertTrue(null != test_external_serialized._mutable_serializable_signature);
			    assertTrue(null != test_external_serialized._service_context);
			    assertEquals(State.IN_MODULE, test_external_serialized._state_name);
			}
		}
		catch (Exception e) {
			System.out.println(ErrorUtils.getLongForm("{1}: {0}", e, e.getClass()));
			fail("Threw exception");
		}
	}
	
	@Test
	public void test_getUnderlyingArtefacts() {
		_logger.info("run test_getUnderlyingArtefacts");
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		
		// (interlude: check errors if called before getSignature
		try {
			test_context.getUnderlyingArtefacts();
			fail("Should have errored");
		}
		catch (Exception e) {
			assertEquals(ErrorUtils.SERVICE_RESTRICTIONS, e.getMessage());
		}
		
		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
												.with(DataBucketBean::_id, "test")
												.with(DataBucketBean::full_name, "/test/get_underlying/artefacts")
												.with(DataBucketBean::modified, new Date())
												.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
														.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.done().get())
														.done().get())
												.done().get();
		
		final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
				.with(SharedLibraryBean::path_name, "/test/lib")
				.done().get();
		test_context.setTechnologyConfig(library);		
		
		// Empty service set:
		test_context.getAnalyticsContextSignature(Optional.of(test_bucket), Optional.empty());		
		final Collection<Object> res1 = test_context.getUnderlyingArtefacts();
		final String exp1 = "class com.ikanow.aleph2.analytics.services.AnalyticsContext:class com.ikanow.aleph2.core.shared.utils.SharedErrorUtils:class com.ikanow.aleph2.data_model.utils.ModuleUtils$ServiceContext:class com.ikanow.aleph2.distributed_services.services.MockCoreDistributedServices:class com.ikanow.aleph2.logging.service.LoggingService:class com.ikanow.aleph2.management_db.mongodb.services.MockMongoDbManagementDbService:class com.ikanow.aleph2.management_db.services.CoreManagementDbService:class com.ikanow.aleph2.search_service.elasticsearch.services.MockElasticsearchIndexService:class com.ikanow.aleph2.shared.crud.elasticsearch.services.MockElasticsearchCrudServiceFactory:class com.ikanow.aleph2.shared.crud.mongodb.services.MockMongoDbCrudServiceFactory:class com.ikanow.aleph2.storage_service_hdfs.services.MockHdfsStorageService";
		assertEquals(exp1, res1.stream().map(o -> o.getClass().toString()).sorted().collect(Collectors.joining(":")));
	}
	
	@Test
	public void test_misc() {
		_logger.info("run test_misc");
		
		assertTrue("Injector created", _app_injector != null);
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		assertEquals(Optional.empty(), test_context.getUnderlyingPlatformDriver(String.class, Optional.empty()));
		assertEquals(Optional.empty(), test_context.getBucket());
		
		test_context.setBucket(BeanTemplateUtils.build(DataBucketBean.class).done().get());
		test_context.resetJob(BeanTemplateUtils.build(AnalyticThreadJobBean.class).done().get());			
		
		// Batch enrichment module accessor
		{
			final Optional<IEnrichmentModuleContext> test1 = test_context.getUnderlyingPlatformDriver(IEnrichmentModuleContext.class, Optional.empty());
			assertTrue(test1.isPresent());
			assertTrue(BatchEnrichmentContext.class.isAssignableFrom(test1.get().getClass()));
			final BatchEnrichmentContext test_batch = (BatchEnrichmentContext) test1.get();
			assertEquals(State.IN_TECHNOLOGY.toString(), test_batch._state_name.toString());
		}
		{
			final Optional<IEnrichmentModuleContext> test1 = test_context.getUnderlyingPlatformDriver(IEnrichmentModuleContext.class, Optional.of("100"));
			assertTrue(test1.isPresent());
			assertTrue(BatchEnrichmentContext.class.isAssignableFrom(test1.get().getClass()));
			final BatchEnrichmentContext test_batch = (BatchEnrichmentContext) test1.get();
			assertEquals(State.IN_MODULE.toString(), test_batch._state_name.toString());
		}
		
		try {
			test_context.emergencyQuarantineBucket(null, null);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "emergencyQuarantineBucket"), e.getMessage());
		}
		try {
			test_context.emergencyDisableBucket(null);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "emergencyDisableBucket"), e.getMessage());
		}
		
		// (This has now been implemented, though not ready to test yet)
//		try {
//			test_context.getBucketStatus(null);
//			fail("Should have thrown exception");
//		}
//		catch (Exception e) {
//			assertEquals(ErrorUtils.NOT_YET_IMPLEMENTED, e.getMessage());
//		}
	}
	
	public static class TestBean {}	
	
	@Test
	public void test_objectStateRetrieval() throws InterruptedException, ExecutionException {
		_logger.info("run test_objectStateRetrieval");
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		final DataBucketBean bucket = BeanTemplateUtils.build(DataBucketBean.class).with("full_name", "TEST_ANALYTICS_CONTEXT").done().get();

		final SharedLibraryBean lib_bean = BeanTemplateUtils.build(SharedLibraryBean.class).with("path_name", "TEST_ANALYTICS_CONTEXT").done().get();
		test_context.setTechnologyConfig(lib_bean);
		
		ICrudService<AssetStateDirectoryBean> dir_a = test_context._core_management_db.getStateDirectory(Optional.empty(), Optional.of(AssetStateDirectoryBean.StateDirectoryType.analytic_thread));
		ICrudService<AssetStateDirectoryBean> dir_e = test_context._core_management_db.getStateDirectory(Optional.empty(), Optional.of(AssetStateDirectoryBean.StateDirectoryType.enrichment));
		ICrudService<AssetStateDirectoryBean> dir_h = test_context._core_management_db.getStateDirectory(Optional.empty(), Optional.of(AssetStateDirectoryBean.StateDirectoryType.harvest));
		ICrudService<AssetStateDirectoryBean> dir_s = test_context._core_management_db.getStateDirectory(Optional.empty(), Optional.of(AssetStateDirectoryBean.StateDirectoryType.library));

		dir_a.deleteDatastore().get();
		dir_e.deleteDatastore().get();
		dir_h.deleteDatastore().get();
		dir_s.deleteDatastore().get();
		assertEquals(0, dir_a.countObjects().get().intValue());
		assertEquals(0, dir_e.countObjects().get().intValue());
		assertEquals(0, dir_h.countObjects().get().intValue());
		assertEquals(0, dir_s.countObjects().get().intValue());
		
		@SuppressWarnings("unused")
		ICrudService<TestBean> s1 = test_context.getGlobalAnalyticTechnologyObjectStore(TestBean.class, Optional.of("test"));
		assertEquals(0, dir_a.countObjects().get().intValue());
		assertEquals(0, dir_e.countObjects().get().intValue());
		assertEquals(0, dir_h.countObjects().get().intValue());
		assertEquals(1, dir_s.countObjects().get().intValue());

		@SuppressWarnings("unused")
		ICrudService<TestBean> e1 = test_context.getBucketObjectStore(TestBean.class, Optional.of(bucket), Optional.of("test"), Optional.of(AssetStateDirectoryBean.StateDirectoryType.enrichment));
		assertEquals(0, dir_a.countObjects().get().intValue());
		assertEquals(1, dir_e.countObjects().get().intValue());
		assertEquals(0, dir_h.countObjects().get().intValue());
		assertEquals(1, dir_s.countObjects().get().intValue());
		
		test_context.setBucket(bucket);
		
		@SuppressWarnings("unused")
		ICrudService<TestBean> a1 = test_context.getBucketObjectStore(TestBean.class, Optional.empty(), Optional.of("test"), Optional.of(AssetStateDirectoryBean.StateDirectoryType.analytic_thread));
		assertEquals(1, dir_a.countObjects().get().intValue());
		assertEquals(1, dir_e.countObjects().get().intValue());
		assertEquals(0, dir_h.countObjects().get().intValue());
		assertEquals(1, dir_s.countObjects().get().intValue());
		
		@SuppressWarnings("unused")
		ICrudService<TestBean> h1 = test_context.getBucketObjectStore(TestBean.class, Optional.empty(), Optional.of("test"), Optional.of(AssetStateDirectoryBean.StateDirectoryType.harvest));
		assertEquals(1, dir_a.countObjects().get().intValue());
		assertEquals(1, dir_e.countObjects().get().intValue());
		assertEquals(1, dir_h.countObjects().get().intValue());
		assertEquals(1, dir_s.countObjects().get().intValue());		

		ICrudService<AssetStateDirectoryBean> dir_e_2 = test_context.getBucketObjectStore(AssetStateDirectoryBean.class, Optional.empty(), Optional.empty(), Optional.empty());
		assertEquals(1, dir_e_2.countObjects().get().intValue());
		
		@SuppressWarnings("unused")
		ICrudService<TestBean> h2 = test_context.getBucketObjectStore(TestBean.class, Optional.empty(), Optional.of("test_2"), Optional.empty());
		assertEquals(2, dir_a.countObjects().get().intValue());
		assertEquals(1, dir_e.countObjects().get().intValue());
		assertEquals(1, dir_h.countObjects().get().intValue());
		assertEquals(1, dir_s.countObjects().get().intValue());		
		assertEquals(2, dir_e_2.countObjects().get().intValue());
		
		@SuppressWarnings("unused")
		ICrudService<TestBean> s2 = test_context.getBucketObjectStore(TestBean.class, Optional.empty(), Optional.of("test_2"), Optional.of(AssetStateDirectoryBean.StateDirectoryType.library));
		assertEquals(2, dir_a.countObjects().get().intValue());
		assertEquals(1, dir_e.countObjects().get().intValue());
		assertEquals(1, dir_h.countObjects().get().intValue());
		assertEquals(2, dir_s.countObjects().get().intValue());
		
	}
	
	@Test
	public void test_fileLocations() throws InstantiationException, IllegalAccessException, ClassNotFoundException, InterruptedException, ExecutionException {
		_logger.info("running test_fileLocations");
		
		try {
			final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
			
			//All we can do here is test the trivial eclipse specific path:
			try { 
				File f = new File(test_context._globals.local_root_dir() + "/lib/aleph2_test_file_locations.jar");
				FileUtils.forceMkdir(f.getParentFile());
				FileUtils.touch(f);
			}
			catch (Exception e) {} // probably already exists:
			

			final List<String> lib_paths = test_context.getAnalyticsContextLibraries(Optional.of(
					ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
						.add(Tuples._2T(IStorageService.class, Optional.<String>empty()))
					.build()));

			//(this doesn't work very well when run in test mode because it's all being found from file)
			assertTrue("Finds some libraries", !lib_paths.isEmpty());
			lib_paths.stream().forEach(lib -> assertTrue("No external libraries: " + lib, lib.contains("aleph2")));
			
			assertTrue("Can find the test JAR or the data model: " +
							lib_paths.stream().collect(Collectors.joining(";")),
						lib_paths.stream().anyMatch(lib -> lib.contains("aleph2_test_file_locations"))
						||
						lib_paths.stream().anyMatch(lib -> lib.contains("aleph2_data_model"))
					);
			
			// Now get the various shared libs

			final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
															.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
															.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2.zip"))
															.done().get();

			final AnalyticThreadJobBean analytic_job2 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
															.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id_XXX") // (not actually possible, just for tesT)
															.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name3.test", "test_analytic_tech_id"))
															.done().get();												
			
			final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::analytic_thread, 
							BeanTemplateUtils.build(AnalyticThreadBean.class)
								.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1, analytic_job2))
							.done().get()
							)
					.done().get();

			final SharedLibraryBean atlib1 = BeanTemplateUtils.build(SharedLibraryBean.class)
												.with(SharedLibraryBean::_id, "test_analytic_tech_id")
												.with(SharedLibraryBean::path_name, "test_analytic_tech_name.jar")
												.done().get();
			
			final SharedLibraryBean atmod1 = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::_id, "id1")
					.with(SharedLibraryBean::path_name, "name1.jar")
					.done().get();
			
			final SharedLibraryBean atmod2 = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::_id, "id2")
					.with(SharedLibraryBean::path_name, "name2.zip")
					.done().get();
			
			final SharedLibraryBean atmod3 = BeanTemplateUtils.build(SharedLibraryBean.class)
					.with(SharedLibraryBean::_id, "id3")
					.with(SharedLibraryBean::path_name, "name3.test")
					.done().get();
			
			test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get()
								.getSharedLibraryStore().storeObjects(Arrays.asList(atlib1, atmod1, atmod2, atmod3)).get();
			
			Map<String, String> mods = test_context.getAnalyticsLibraries(Optional.of(test_bucket), Arrays.asList(analytic_job1, analytic_job2)).get();
			assertTrue("name1", mods.containsKey("name1.jar") && mods.get("name1.jar").endsWith("id1.cache.jar"));
			assertTrue("name2", mods.containsKey("name2.zip") && mods.get("name2.zip").endsWith("id2.cache.zip"));
			assertTrue("name3", mods.containsKey("name3.test") && mods.get("name3.test").endsWith("id3.cache.misc.test"));
			assertTrue("test_analytic_tech_name", mods.containsKey("test_analytic_tech_name.jar") && mods.get("test_analytic_tech_name.jar").endsWith("test_analytic_tech_id.cache.jar"));
		}
		catch (Exception e) {
			try {
				e.printStackTrace();
			}
			catch (Exception ee) {
				System.out.println(ErrorUtils.getLongForm("{1}: {0}", e, e.getClass()));
			}
			fail("Threw exception");
		}
		
	}
	
	public interface StringAnalyticsAccessContext extends IAnalyticsAccessContext<String> {}
	@SuppressWarnings("rawtypes")
	public interface HadoopAnalyticsAccessContext extends IAnalyticsAccessContext<InputFormat> {}
	
	@Test
	public void test_serviceInputsAndOutputs() throws InterruptedException, ExecutionException {
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);		
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
																						.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
																					.done().get();
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
			.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input3 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "mistake")
			.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input4 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/aleph2_external/anything_i_want")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service.alternate")
			.done().get();		
		
		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class).done().get();
		
		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, analytic_input2, analytic_input3))
				.with(AnalyticThreadJobBean::output, analytic_output)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();
		
		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test")
				.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
						.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class) //(else search_index won't exist)
								.done().get())
						.done().get())
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
							.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1)
							)
						.done().get()
						)
				.done().get();
				
		
		// Check that it fails if the input is not present in the DB
		try {
			test_context.getServiceInput(StringAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, analytic_input1);
			fail("Should have thrown security exception");
		}
		catch (Exception e) {}
		
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(test_bucket, true).get();

		// (check bucket not present failure case)
		try {
			test_context.getServiceInput(StringAnalyticsAccessContext.class, Optional.empty(), analytic_job1, analytic_input2);
			fail("Should have thrown security exception");
		}
		catch (Exception e) {}
		
		test_context.setBucket(test_bucket);
		
		// Check standard cases
		
		assertEquals(Optional.empty(), test_context.getServiceInput(StringAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, analytic_input1));
		assertEquals(Optional.empty(), test_context.getServiceInput(StringAnalyticsAccessContext.class, Optional.empty(), analytic_job1, analytic_input2));
		assertEquals(Optional.empty(), test_context.getServiceInput(StringAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, analytic_input3));
		
		assertEquals(Optional.empty(), test_context.getServiceOutput(StringAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, "search_index_service"));
		assertEquals(Optional.empty(), test_context.getServiceOutput(StringAnalyticsAccessContext.class, Optional.empty(), analytic_job1, "storage_service"));
		assertEquals(Optional.empty(), test_context.getServiceOutput(StringAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, "random_string"));
		
		final MockServiceContext mock_service_context = new MockServiceContext();
		mock_service_context.addService(IStorageService.class, Optional.of("other_name"), test_context._storage_service);
		assertEquals(Optional.of(test_context._storage_service), AnalyticsContext.getUnderlyingService(mock_service_context, "storage_service", Optional.of("other_name")));

		mock_service_context.addService(ISearchIndexService.class, Optional.of("alternate"), test_context._service_context.getSearchIndexService().get());
		mock_service_context.addService(ISecurityService.class, Optional.empty(), test_context._security_service);
		mock_service_context.addService(IStorageService.class, Optional.empty(), test_context._storage_service);
		mock_service_context.addService(IManagementDbService.class, Optional.empty(), test_context._core_management_db);
		mock_service_context.addService(IManagementDbService.class, IManagementDbService.CORE_MANAGEMENT_DB, test_context._core_management_db);
		mock_service_context.addService(ICoreDistributedServices.class, Optional.empty(), test_context._distributed_services);
		mock_service_context.addService(ILoggingService.class, Optional.empty(), test_context._logging_service);
		mock_service_context.addGlobals(BeanTemplateUtils.build(GlobalPropertiesBean.class).done().get());
		final AnalyticsContext mock_to_test = new AnalyticsContext(mock_service_context);
		assertEquals(0, mock_to_test._mutable_state.extra_auto_context_libs.size());
		assertTrue(mock_to_test.getServiceInput(HadoopAnalyticsAccessContext.class, Optional.of(test_bucket), analytic_job1, analytic_input4).isPresent());
		assertEquals(1, mock_to_test._mutable_state.extra_auto_context_libs.size());
	}
		
	@Test
	public void test_batch_inputAndOutputUtilities() throws InterruptedException, ExecutionException {

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output_stream_transient =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.streaming)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
				.done().get();		

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output_batch_permanent =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.streaming_and_batch)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, false)
				.done().get();		
		
		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output_batch_transient =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
				.done().get();		
		

		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);		

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2a =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "test_transient_internal_missing")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2b =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/this_bucket:test_transient_internal_not_transient")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input3 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/this_bucket:")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input4a =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket:test_transient_external_present")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input4b =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket:test_transient_external_not_batch")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input5a =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input5b =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/this_bucket")
				.done().get();

		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_name1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, 
						analytic_input2a, analytic_input2b,
						analytic_input3, 
						analytic_input4a, analytic_input4b,
						analytic_input5a, analytic_input5b))
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		final AnalyticThreadJobBean analytic_job2 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_transient_internal_not_transient")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList())
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.with(AnalyticThreadJobBean::output, analytic_output_batch_permanent)
				.done().get();

		final AnalyticThreadJobBean analytic_job_other_1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_transient_external_present")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList())
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.with(AnalyticThreadJobBean::output, analytic_output_batch_transient)
				.done().get();

		final AnalyticThreadJobBean analytic_job_other_2 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_transient_external_not_batch")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList())
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.with(AnalyticThreadJobBean::output, analytic_output_stream_transient)
				.done().get();
		
		try {
			test_context.getOutputPath(Optional.empty(), analytic_job1);
			fail("Expected exception on getOutputPath");
		}
		catch (Exception e) {}

		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "this_bucket")
				.with(DataBucketBean::full_name, "/this_bucket")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1, analytic_job2)
								)
								.done().get()
						)
						.done().get();

		final DataBucketBean other_bucket = BeanTemplateUtils.clone(test_bucket)
				.with(DataBucketBean::_id, "other_bucket")
				.with(DataBucketBean::full_name, "/other_bucket")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job_other_1, analytic_job_other_2)
								)
								.done().get()
						)
						.done();

		test_context.setBucket(test_bucket);


		// Check that it fails if the bucket is not present (or not readable)

		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().deleteDatastore().get();
		assertEquals(0, test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().countObjects().get().intValue());

		try {
			test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input4a);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.INPUT_PATH_NOT_A_TRANSIENT_BATCH, 
					"/this_bucket", "test_name1", "/other_bucket", "test_transient_external_present"), 
					e.getMessage());
		}

		// Add the bucket to the CRUD store:

		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(other_bucket, true).get();

		// Now do all the other checks:

		assertEquals(Arrays.asList(), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input1));
		try {
			test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input2a);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.INPUT_PATH_NOT_A_TRANSIENT_BATCH, 
					"/this_bucket", "test_name1", "/this_bucket", "test_transient_internal_missing"), 
					e.getMessage());
		}
		try {
			test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input2b);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.INPUT_PATH_NOT_A_TRANSIENT_BATCH, 
					"/this_bucket", "test_name1", "/this_bucket", "test_transient_internal_not_transient"), 
					e.getMessage());
		}
		assertEquals(Arrays.asList("/app/aleph2//data//this_bucket/managed_bucket/import/ready/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input3));
		assertEquals(Arrays.asList("/app/aleph2//data//other_bucket/managed_bucket/import/transient/test_transient_external_present/current/**/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input4a));
		try {
			test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input4b);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.INPUT_PATH_NOT_A_TRANSIENT_BATCH, 
					"/this_bucket", "test_name1", "/other_bucket", "test_transient_external_not_batch"), 
					e.getMessage());
		}
		assertEquals(Arrays.asList("/app/aleph2//data//this_bucket/managed_bucket/import/ready/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input5a));
		assertEquals(Arrays.asList("/app/aleph2//data//this_bucket/managed_bucket/import/ready/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input5b));
	}
	
	@Test
	public void test_storageService_inputAndOutputUtilities() throws InterruptedException, ExecutionException {
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);		

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket:raw")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket:json")
				.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input3 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket:processed")
				.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input4 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/other_bucket")
				.done().get();		

		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_name1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, 
						analytic_input2,
						analytic_input3, 
						analytic_input4))
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		try {
			test_context.getOutputPath(Optional.empty(), analytic_job1);
			fail("Expected exception on getOutputPath");
		}
		catch (Exception e) {}

		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "this_bucket")
				.with(DataBucketBean::full_name, "/this_bucket")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1)
								)
								.done().get()
						)
						.done().get();

		final DataBucketBean other_bucket = BeanTemplateUtils.clone(test_bucket)
				.with(DataBucketBean::_id, "other_bucket")
				.with(DataBucketBean::full_name, "/other_bucket")
				.with(DataBucketBean::analytic_thread, null) 
						.done();

		test_context.setBucket(test_bucket);


		// Check that it fails if the bucket is not present (or not readable)

		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().deleteDatastore().get();
		assertEquals(0, test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().countObjects().get().intValue());

		try {
			test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input1);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.BUCKET_NOT_FOUND_OR_NOT_READABLE, "/other_bucket"), e.getMessage());
		}

		// Add the bucket to the CRUD store:

		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(other_bucket).get();

		// Now do all the other checks:

		assertEquals(Arrays.asList("/app/aleph2//data//other_bucket/managed_bucket/import/stored/raw/current/**/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input1));
		assertEquals(Arrays.asList("/app/aleph2//data//other_bucket/managed_bucket/import/stored/json/current/**/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input2));
		assertEquals(Arrays.asList("/app/aleph2//data//other_bucket/managed_bucket/import/stored/processed/current/**/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input3));
		assertEquals(Arrays.asList("/app/aleph2//data//other_bucket/managed_bucket/import/stored/processed/current/**/*"), test_context.getInputPaths(Optional.of(test_bucket), analytic_job1, analytic_input4));
	}
	
	@Test
	public void test_streaming_inputAndOutputUtilities() throws InterruptedException, ExecutionException {
				
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);		
		
		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output1 =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.streaming)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
				.done().get();		

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output2 =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.streaming_and_batch)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, false)
				.done().get();		
		
		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output_not_streaming =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, false)
				.done().get();		
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
			.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "stream")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "test2")
			.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input3 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "stream")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test3:")
			.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input4 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "stream")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test4:test")
			.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input5 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "stream")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test5:$start")
			.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input6 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "stream")
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::resource_name_or_id, "/test6:$end")
			.done().get();
		
		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_name1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, analytic_input2, analytic_input3))
				.with(AnalyticThreadJobBean::output, analytic_output1)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();
		
		final AnalyticThreadJobBean analytic_job2 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test_name2")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input4, analytic_input5, analytic_input6))
				.with(AnalyticThreadJobBean::output, analytic_output2)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		final AnalyticThreadJobBean analytic_job_not_streaming = BeanTemplateUtils.clone(analytic_job1)
																	.with(AnalyticThreadJobBean::output, analytic_output_not_streaming)
																	.done();																		
		
		try {
			test_context.getOutputPath(Optional.empty(), analytic_job1);
			fail("Expected exception on getOutputPath");
		}
		catch (Exception e) {}

		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test")
				.with(DataBucketBean::full_name, "/test")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
							.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1)
							)
						.done().get()
						)
				.done().get();

		final DataBucketBean test_bucket_not_streaming = BeanTemplateUtils.clone(test_bucket)
															.with(DataBucketBean::analytic_thread, 
																BeanTemplateUtils.build(AnalyticThreadBean.class)
																	.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job_not_streaming)
																	)
																.done().get()
																)
															.done();
		
		test_context.setBucket(test_bucket);

		assertEquals(false, test_context.checkForListeners(Optional.of(test_bucket), analytic_job1)); 
				
		assertEquals(Optional.empty(), test_context.getOutputTopic(Optional.of(test_bucket_not_streaming), analytic_job_not_streaming));
		
		assertEquals(Optional.of(KafkaUtils.bucketPathToTopicName("/test", Optional.of("test_name1"))), test_context.getOutputTopic(Optional.of(test_bucket), analytic_job1));
		assertEquals(Optional.of(KafkaUtils.bucketPathToTopicName("/test", Optional.of("$end"))), test_context.getOutputTopic(Optional.empty(), analytic_job2));

		// Check that it fails if the bucket is not present (or not readable)
		
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().deleteDatastore().get();
		assertEquals(0, test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().countObjects().get().intValue());

		// This should pass:
		assertTrue("Can always getInputTopics on myself", !test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input2).isEmpty());
	
		try {
			test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input3);
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals("java.lang.RuntimeException: " + ErrorUtils.get(ErrorUtils.BUCKET_NOT_FOUND_OR_NOT_READABLE, "/test3"), e.getMessage());
		}
		
		// Add the bucket to the CRUD store:
		
		Stream.of("/test3", "/test4", "/test5", "/test6").forEach(Lambdas.wrap_consumer_u(bucket_path -> {
			test_context._service_context.getService(IManagementDbService.class, 
					Optional.empty()).get().getDataBucketStore().storeObject(
							BeanTemplateUtils.clone(test_bucket)
								.with(DataBucketBean::_id, bucket_path.substring(1))
								.with(DataBucketBean::full_name, bucket_path)
							.done(), true).get();
		}));
		
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test3", Optional.empty())), test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input3));
		
		// Now do all the other checks:
		
		assertEquals(Arrays.asList(), test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input1));
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test", Optional.of("test2"))), test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input2));
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test3", Optional.empty())), test_context.getInputTopics(Optional.of(test_bucket), analytic_job1, analytic_input3));
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test4", Optional.of("test"))), test_context.getInputTopics(Optional.empty(), analytic_job2, analytic_input4));
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test5", Optional.empty())), test_context.getInputTopics(Optional.empty(), analytic_job2, analytic_input5));
		assertEquals(Arrays.asList(KafkaUtils.bucketPathToTopicName("/test6", Optional.of("$end"))), test_context.getInputTopics(Optional.empty(), analytic_job2, analytic_input6));
	}
	
	@Test
	public void test_objectEmitting_normal() throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, ClassNotFoundException {
		test_objectEmitting(true, true);
	}
	
	@Test
	public void test_objectEmitting_pingPong() throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, ClassNotFoundException {
		//(will start by writing into pong)
		Tuple2<DataBucketBean, AnalyticsContext> saved = test_objectEmitting(false, true); //(ping)
		test_objectEmitting(false, false); //(ping)
		saved._2().completeBucketOutput(saved._1());
		Tuple2<DataBucketBean, AnalyticsContext> saved2 = test_objectEmitting(false, false); //(pong)
		saved2._2().completeBucketOutput(saved2._1());
	}
	
	@SuppressWarnings("null")
	public Tuple2<DataBucketBean, AnalyticsContext> test_objectEmitting(boolean preserve_out, boolean first_time) throws InterruptedException, ExecutionException, InstantiationException, IllegalAccessException, ClassNotFoundException {
		_logger.info("run test_objectEmitting: " + preserve_out);

		//(See _FileSystemChecks for file output) 		
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
				.done().get();
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output =  
				BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
					.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::preserve_existing_data, preserve_out)
				.done().get();

		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::name, "test_analytic_tech_name")				
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, analytic_input2))
				.with(AnalyticThreadJobBean::output, analytic_output)
				.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch) // (matters in the ping/pong case, only supported in batch)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test")
				.with(DataBucketBean::full_name, "/test/" + (preserve_out?"preserve":"temporary"))
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1)
								)
								.done().get()
						)
				.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
						.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
								.done().get())
						.done().get())						
				.done().get();

		final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
				.with(SharedLibraryBean::path_name, "/test/lib")
				.done().get();
		test_context.setBucket(test_bucket);
		test_context.setTechnologyConfig(library);
		test_context.resetJob(analytic_job1);
		
		// Empty service set:
		final String signature = test_context.getAnalyticsContextSignature(Optional.of(test_bucket), Optional.empty());

		@SuppressWarnings("unchecked")
		final ICrudService<DataBucketBean> raw_mock_db =
				test_context._core_management_db.getDataBucketStore().getUnderlyingPlatformDriver(ICrudService.class, Optional.empty()).get();
		raw_mock_db.deleteDatastore().get();
		assertEquals(0L, (long)raw_mock_db.countObjects().get());
		raw_mock_db.storeObject(test_bucket).get();		
		assertEquals(1L, (long)raw_mock_db.countObjects().get());
		
		final IAnalyticsContext test_external1a = ContextUtils.getAnalyticsContext(signature);		
		final ISearchIndexService check_index = test_external1a.getServiceContext().getService(ISearchIndexService.class, Optional.empty()).get();		

		if (!preserve_out) {
			Thread.sleep(1000L);
			assertTrue(check_index.getDataService().get().getPrimaryBufferName(test_bucket, Optional.empty()).isPresent()); // (one of pong or ping)
		}
		Optional<String> write_buffer = check_index.getDataService().get().getPrimaryBufferName(test_bucket, Optional.empty())
				.map(name -> name.equals(IGenericDataService.SECONDARY_PING) ? IGenericDataService.SECONDARY_PONG: IGenericDataService.SECONDARY_PING);
		
		System.out.println("GET WRITE BUFFER NAME = " + write_buffer);		
		
		//(internal)
//		ISearchIndexService check_index = test_context.getService(ISearchIndexService.class, Optional.empty()).get();
		//(external)
		final ICrudService<JsonNode> crud_check_index = 
				check_index.getDataService()
					.flatMap(s -> s.getWritableDataService(JsonNode.class, test_bucket, Optional.empty(), write_buffer))
					.flatMap(IDataWriteService::getCrudService)
					.get();
		crud_check_index.deleteDatastore();
		Thread.sleep(2000L); // (wait for datastore deletion to flush)
		assertEquals(0, crud_check_index.countObjects().get().intValue());
		
		final JsonNode jn1 = _mapper.createObjectNode().put("test", "test1");
		final JsonNode jn2 = _mapper.createObjectNode().put("test", "test2");
				
		//(try some errors)
		try {
			test_external1a.emitObject(Optional.empty(), analytic_job1, Either.left(jn1), Optional.of(BeanTemplateUtils.build(AnnotationBean.class).done().get()));
			fail("Should have thrown exception");
		}
		catch (Exception e) {
			assertEquals(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "annotations"), e.getMessage());
		}
		String topic = KafkaUtils.bucketPathToTopicName(test_bucket.full_name(), Optional.of("$end"));
		WrappedConsumerIterator iter = null;
		if ( !first_time)
			iter = (WrappedConsumerIterator)test_context._distributed_services.consumeAs(topic, Optional.empty(), Optional.empty());
		test_external1a.emitObject(Optional.of(test_bucket), analytic_job1, Either.left(jn1), Optional.empty());
		test_external1a.emitObject(Optional.empty(), analytic_job1, Either.left(jn2), Optional.empty());
		// (create topic now)
		
		test_context._distributed_services.createTopic(topic, Optional.empty());
		if ( first_time )
			iter = (WrappedConsumerIterator)test_context._distributed_services.consumeAs(topic, Optional.empty(), Optional.empty());		
		test_external1a.emitObject(Optional.empty(), analytic_job1, Either.right(
				ImmutableMap.<String, Object>builder().put("test", "test3").put("extra", "test3_extra").build()
				), Optional.empty());
				
		test_context.flushBatchOutput(Optional.of(test_bucket), analytic_job1); //(this needs to just not die)
		test_external1a.flushBatchOutput(Optional.of(test_bucket), analytic_job1); //(this actually flushes the buffer)
		
		for (int i = 0; i < 60; ++i) {
			Thread.sleep(1000L);
			if (crud_check_index.countObjects().get().intValue() >= 2) {
				_logger.info("(Found objects after " + i + " seconds)");
				break;
			}
		}
		
		//DEBUG
		//System.out.println(crud_check_index.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get().indexContext().getReadableIndexList(Optional.empty()));
		//System.out.println(crud_check_index.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get().typeContext().getReadableTypeList());
		
		assertEquals(3, crud_check_index.countObjects().get().intValue());
		assertEquals("{\"test\":\"test3\",\"extra\":\"test3_extra\"}", ((ObjectNode)
				crud_check_index.getObjectBySpec(CrudUtils.anyOf().when("test", "test3")).get().get()).remove(Arrays.asList("_id", "_type", "_index")).toString());
	
		Thread.sleep(5000); //wait a few seconds for producers to dump batch
		List<String> kafka = Optionals.streamOf(iter, false).collect(Collectors.toList());
		assertEquals(first_time ? 1 : 3, kafka.size()); //(second time through the topic exists so all 3 emits work)
		iter.close();
		//return Tuples._2T(test_bucket, check_index.getDataService().get());
		return Tuples._2T(test_bucket, test_context);
	}

	@Test
	public void test_streamingPipeline() throws JsonProcessingException, IOException, InterruptedException {
		_logger.info("running test_streamingPipeline");
		
		final ObjectMapper mapper = BeanTemplateUtils.configureMapper(Optional.empty());
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
				.done().get();
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
																	.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, MasterEnrichmentType.streaming)
																	.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
																					.done().get();

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output_no_streaming =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
								.done().get();
		
		
		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "test1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1, analytic_input2))
				.with(AnalyticThreadJobBean::output, analytic_output)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		final DataBucketBean test_bucket = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test")
				.with(DataBucketBean::full_name, "/TEST/ANALYICS/CONTEXT")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(analytic_job1)
								)
								.done().get()
						)
				.with("data_schema", BeanTemplateUtils.build(DataSchemaBean.class)
						.with("search_index_schema", BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
								.done().get())
						.done().get())						
				.done().get();

		final SharedLibraryBean library = BeanTemplateUtils.build(SharedLibraryBean.class)
				.with(SharedLibraryBean::path_name, "/test/lib")
				.done().get();
		test_context.setTechnologyConfig(library);
		
		test_context._distributed_services.createTopic(BucketUtils.getUniqueSignature("/TEST/ANALYICS/CONTEXT", Optional.of("test1")), Optional.empty());
		
		String message1 = "{\"key\":\"val\"}";
		String message2 = "{\"key\":\"val2\"}";
		String message3 = "{\"key\":\"val3\"}";
		String message4 = "{\"key\":\"val4\"}";
		Map<String, Object> msg3 = ImmutableMap.<String, Object>builder().put("key", "val3").build();
		Map<String, Object> msg4 = ImmutableMap.<String, Object>builder().put("key", "val4").build();
		//currently mock cds produce does nothing
		try {
			test_context.sendObjectToStreamingPipeline(Optional.empty(), analytic_job1, Either.left(mapper.readTree(message1)), Optional.empty());
			fail("Should fail, bucket not set and not specified");
		}
		catch (Exception e) {}
		test_context.setBucket(test_bucket);
		assertEquals(test_bucket, test_context.getBucket().get());
		Iterator<String> iter = test_context._distributed_services.consumeAs(BucketUtils.getUniqueSignature("/TEST/ANALYICS/CONTEXT", Optional.of("test1")), Optional.empty(), Optional.empty());
		
		test_context.sendObjectToStreamingPipeline(Optional.empty(), analytic_job1, Either.left(mapper.readTree(message1)), Optional.empty());
		test_context.sendObjectToStreamingPipeline(Optional.of(test_bucket), analytic_job1, Either.left(mapper.readTree(message2)), Optional.empty());
		test_context.sendObjectToStreamingPipeline(Optional.empty(), analytic_job1, Either.right(msg3), Optional.empty());
		test_context.sendObjectToStreamingPipeline(Optional.of(test_bucket), analytic_job1, Either.right(msg4), Optional.empty());

		//(just send a quick message out on a different job name so it will fail silently)
		test_context.sendObjectToStreamingPipeline(Optional.of(test_bucket), BeanTemplateUtils.clone(analytic_job1).with("name", "different").done(), 
				Either.right(msg4), Optional.empty());
		//(just send a quick message out with streaming turned off so it will fail silently)
		test_context.sendObjectToStreamingPipeline(Optional.of(test_bucket), 
				BeanTemplateUtils.clone(analytic_job1).with("output", analytic_output_no_streaming).done(), 
				Either.right(msg4), Optional.empty());		
		
		try {
			test_context.sendObjectToStreamingPipeline(Optional.empty(), analytic_job1, Either.left(mapper.readTree(message1)), Optional.of(BeanTemplateUtils.build(AnnotationBean.class).done().get()));
			fail("Should fail, annotation specified");
		}
		catch (Exception e) {}

		
		final HashSet<String> mutable_set = new HashSet<>(Arrays.asList(message1, message2, message3, message4));
		
		//nothing will be in consume
		Thread.sleep(5000); //wait a few seconds for producers to dump batch
		
		long count = 0;
		while ( iter.hasNext() ) {
			String msg = iter.next();
			assertTrue("Sent this message: " + msg, mutable_set.remove(msg));
			count++;
		}
		assertEquals(4,count);
	}
	
	@Test
	public void test_externalEmit() throws JsonProcessingException, IOException, InterruptedException {
		test_externalEmit_worker(false);
	}
	@Test
	public void test_externalEmit_testMode() throws JsonProcessingException, IOException, InterruptedException {
		test_externalEmit_worker(true);
	}
		
	public void test_externalEmit_worker(boolean is_test) throws JsonProcessingException, IOException, InterruptedException {
		final MockSecurityService mock_security = (MockSecurityService) _service_context.getSecurityService();
		
		// Create some buckets:

		// 0) My bucket
		
		final AnalyticsContext test_context = _app_injector.getInstance(AnalyticsContext.class);
		
		final AnalyticThreadJobInputBean input =
				BeanTemplateUtils.build(AnalyticThreadJobInputBean.class)
					.with(AnalyticThreadJobInputBean::resource_name_or_id, "/test")
				.done().get();
		
		final AnalyticThreadJobBean job = 
				BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "test")
					.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean::inputs, Arrays.asList(input))
				.done().get();
		
		final DataBucketBean my_bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::full_name, is_test ? "/aleph2_testing/useriid/test/me" : "/test/me")
				.with(DataBucketBean::owner_id, "me")
				.with(DataBucketBean::external_emit_paths, Arrays.asList("/test/s**", "/test/analytics/no_input", "/test/noperms/stream", "/test/notpresent/stream"))
				// (include the noperms, will still fail)
			.done().get();		
		
		test_context.setBucket(my_bucket);
		
		// 1) Streaming enrichment bucket
		
		final DataBucketBean stream_bucket = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, is_test ? "/test/stream/test" : "/test/stream")
					.with(DataBucketBean::master_enrichment_type, MasterEnrichmentType.streaming)
				.done().get();
				
		// save in the DB:
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(stream_bucket, true).join();
		mock_security.setUserMockRole("me", stream_bucket.full_name(), ISecurityService.ACTION_READ_WRITE, true);
		
		// 2) Batch analytic bucket
				
		//(see TestAnalyticsContext_FileSystemChecks)
		
		// 3) Analytic bucket that doesn't use a "self" input
		
		final DataBucketBean analytic_bucket_no_self_input = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test/analytics/no_input")
					.with(DataBucketBean::analytic_thread,
							BeanTemplateUtils.build(AnalyticThreadBean.class)
								.with(AnalyticThreadBean::jobs, Arrays.asList(job))
							.done().get()
							)
				.done().get();
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(analytic_bucket_no_self_input, true).join();
		mock_security.setUserMockRole("me", analytic_bucket_no_self_input.full_name(), ISecurityService.ACTION_READ_WRITE, true);
		
		// 4a) Bucket that isn't in the perm list

		final DataBucketBean analytic_bucket_not_in_perm_list = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test/analytics/no_input/not_in_perm_list")
					.with(DataBucketBean::analytic_thread,
							BeanTemplateUtils.build(AnalyticThreadBean.class)
								.with(AnalyticThreadBean::jobs, Arrays.asList(job))
							.done().get()
							)
				.done().get();
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(analytic_bucket_not_in_perm_list, true).join();
		mock_security.setUserMockRole("me", analytic_bucket_no_self_input.full_name(), ISecurityService.ACTION_READ_WRITE, true);
		
		// 4b) Bucket that we don't have write permission for
		
		final DataBucketBean stream_bucket_no_perms = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test/noperms/stream")
					.with(DataBucketBean::master_enrichment_type, MasterEnrichmentType.streaming)
				.done().get();		
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(stream_bucket_no_perms, true).join();
		
		// 5) bucket that's not even in the DB
		
		final DataBucketBean stream_bucket_not_in_db = 
				BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::full_name, "/test/notpresent/stream")
					.with(DataBucketBean::master_enrichment_type, MasterEnrichmentType.streaming)
				.done().get();		
		test_context._service_context.getService(IManagementDbService.class, Optional.empty()).get().getDataBucketStore().storeObject(stream_bucket_no_perms, true).join();
		mock_security.setUserMockRole("me", stream_bucket_not_in_db.full_name(), ISecurityService.ACTION_READ_WRITE, true);
		
		// Tests:
		
		//1) Streaming enrichment
		{
			Validation<BasicMessageBean, JsonNode> ret_val_1 =
					test_context.emitObject(Optional.of(stream_bucket), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"stream1\"}")), Optional.empty());
			
			final String listen_topic = test_context._distributed_services.generateTopicName(stream_bucket.full_name(), Optional.empty());
			
			// Will fail because nobody is listening
			assertTrue("Should fail: " + ret_val_1.toString(), ret_val_1.isFail());

			// check it's cached though
			assertEquals(Either.right(listen_topic), test_context._mutable_state.external_buckets.get(stream_bucket.full_name()));
			
			// Start listening
			test_context._distributed_services.createTopic(listen_topic, Optional.empty());
			Iterator<String> iter = test_context._distributed_services.consumeAs(listen_topic, Optional.empty(), Optional.empty());
			
			Validation<BasicMessageBean, JsonNode> ret_val_2 =
					test_context.emitObject(Optional.of(stream_bucket), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"stream2\"}")), Optional.empty());
			
			assertTrue("Should work: " + ret_val_2.toString(), ret_val_2.isSuccess());

			//grab message to check actually emitted			
			Thread.sleep(5000); //wait a few seconds for producers to dump batch
			
			long count = 0;			
			while ( iter.hasNext() ) {
				String msg = iter.next();
				assertTrue("Sent this message: " + msg, msg.equals(ret_val_2.success().toString()));
				count++;
			}
			assertEquals(is_test ? 0 : 1, count);			
			
		}
		// 2) Batch analytic bucket
		
		//(see TestAnalyticsContext_FileSystemChecks)
		
		// 3)  Analytic bucket that doesn't use a "self" input - NOW TREATS AS BATCH
		{
			Validation<BasicMessageBean, JsonNode> ret_val_1 =
					test_context.emitObject(Optional.of(analytic_bucket_no_self_input), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"batch_fail\"}")), Optional.empty());
		
			final String listen_topic = test_context._distributed_services.generateTopicName(analytic_bucket_no_self_input.full_name(), Optional.empty());			
			test_context._distributed_services.createTopic(listen_topic, Optional.empty());
			
			// Will succeed and default to batch (previously: Will fail because nobody is listening)
			assertTrue("Should succeed: " + ret_val_1.toString(), ret_val_1.isSuccess());		
			
			// check failure is cached though
			assertTrue("Not cached: " + test_context._mutable_state.external_buckets, test_context._mutable_state.external_buckets.containsKey(analytic_bucket_no_self_input.full_name()));
			assertTrue(null != test_context._mutable_state.external_buckets.get(analytic_bucket_no_self_input.full_name()).left().value());
			
		}
		
		// 4a) bucket that we have perms for but not declared
		
		{
			Validation<BasicMessageBean, JsonNode> ret_val_1 =
					test_context.emitObject(Optional.of(analytic_bucket_not_in_perm_list), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"stream1\"}")), Optional.empty());

			final String listen_topic = test_context._distributed_services.generateTopicName(analytic_bucket_not_in_perm_list.full_name(), Optional.empty());
			test_context._distributed_services.createTopic(listen_topic, Optional.empty());
			
			// Will fail because nobody has write perms
			assertTrue("Should fail: " + ret_val_1.toString(), ret_val_1.isFail());
			
			// check failure is cached though
			assertTrue("Not cached: " + test_context._mutable_state.external_buckets, test_context._mutable_state.external_buckets.containsKey(analytic_bucket_not_in_perm_list.full_name()));
			assertEquals(null, test_context._mutable_state.external_buckets.get(analytic_bucket_not_in_perm_list.full_name()).right().value());			
		}
		
		
		// 4b) Bucket that we don't have write permission for
		{
			Validation<BasicMessageBean, JsonNode> ret_val_1 =
					test_context.emitObject(Optional.of(stream_bucket_no_perms), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"stream1\"}")), Optional.empty());

			final String listen_topic = test_context._distributed_services.generateTopicName(stream_bucket_no_perms.full_name(), Optional.empty());
			test_context._distributed_services.createTopic(listen_topic, Optional.empty());
			
			// Will fail because nobody has write perms
			assertTrue("Should fail: " + ret_val_1.toString(), ret_val_1.isFail());
			
			// check failure is cached though
			assertTrue("Not cached: " + test_context._mutable_state.external_buckets, test_context._mutable_state.external_buckets.containsKey(stream_bucket_no_perms.full_name()));
			assertEquals(null, test_context._mutable_state.external_buckets.get(stream_bucket_no_perms.full_name()).right().value());
			
		}
		
		// 5) bucket that's not even in the DB
		{
			Validation<BasicMessageBean, JsonNode> ret_val_1 =
					test_context.emitObject(Optional.of(stream_bucket_not_in_db), job, Either.left((ObjectNode)_mapper.readTree("{\"test\":\"stream1\"}")), Optional.empty());
			
			final String listen_topic = test_context._distributed_services.generateTopicName(stream_bucket_not_in_db.full_name(), Optional.empty());
			test_context._distributed_services.createTopic(listen_topic, Optional.empty());
			
			// Will fail because not in the DB
			assertTrue("Should fail: " + ret_val_1.toString(), ret_val_1.isFail());
			
			// check failure is cached though
			assertTrue("Not cached: " + test_context._mutable_state.external_buckets, test_context._mutable_state.external_buckets.containsKey(stream_bucket_not_in_db.full_name()));
			assertEquals(null, test_context._mutable_state.external_buckets.get(stream_bucket_not_in_db.full_name()).right().value());
		}
		
		
	}

	//TODO (ALEPH-12): test sub-buckets once implemented
}
