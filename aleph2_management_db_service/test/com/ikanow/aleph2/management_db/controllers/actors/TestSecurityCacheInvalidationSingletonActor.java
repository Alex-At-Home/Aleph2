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
package com.ikanow.aleph2.management_db.controllers.actors;

import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.ikanow.aleph2.distributed_services.services.MockCoreDistributedServices;
import com.ikanow.aleph2.management_db.services.ManagementDbActorContext;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

public class TestSecurityCacheInvalidationSingletonActor {
	private static final Logger _logger = LogManager.getLogger();	

	@Inject 
	protected IServiceContext _service_context = null;	
	
	protected ICoreDistributedServices _cds = null;
	protected IManagementDbService _core_mgmt_db = null;
	protected ManagementDbActorContext _actor_context = null;
	protected ActorRef testMonitor =  null;
	

	@After
	public void tidyUp() {
		// (kill current kafka queue)
		ManagementDbActorContext.get().getServiceContext().getService(ICoreDistributedServices.class, Optional.empty())
			.filter(x -> MockCoreDistributedServices.class.isAssignableFrom(x.getClass()))
			.map(x -> (MockCoreDistributedServices)x)
			.ifPresent(x -> x.kill());
			;
	}
	
	@SuppressWarnings("deprecation")
	@Before
	public void testSetup() throws Exception {
		
		if (null != _service_context) {
			return;
		}
		final String temp_dir = System.getProperty("java.io.tmpdir") + File.separator;
		
		ManagementDbActorContext.unsetSingleton();
		
		// OK we're going to use guice, it was too painful doing this by hand...				
		Config config = ConfigFactory.parseReader(new InputStreamReader(this.getClass().getResourceAsStream("actor_test.properties")))
							.withValue("globals.local_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
							.withValue("globals.local_cached_jar_dir", ConfigValueFactory.fromAnyRef(temp_dir))
							.withValue("globals.distributed_root_dir", ConfigValueFactory.fromAnyRef(temp_dir))
							.withValue("globals.local_yarn_config_dir", ConfigValueFactory.fromAnyRef(temp_dir));
		
		Injector app_injector = ModuleUtils.createTestInjector(Arrays.asList(), Optional.of(config));	
		app_injector.injectMembers(this);
	
		_cds = _service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();
		MockCoreDistributedServices mcds = (MockCoreDistributedServices) _cds;
		mcds.setApplicationName("DataImportManager");
		
		_core_mgmt_db = _service_context.getCoreManagementDbService();
		
		//(this has to happen after the call to _service_context.getCoreManagementDbService() - bizarrely the actor context is not set before that?!)
		_actor_context = ManagementDbActorContext.get();		
	}
	

	@Test
	public void testSecurityCacheInvalidationActor() throws InterruptedException {
		_cds.waitForAkkaJoin(Optional.of(Duration.create(10, TimeUnit.SECONDS)));
		Thread.sleep(2000L);
		
		/*Optional<ActorRef> oTestMonitor = _cds.createSingletonActor(ActorUtils.SECURITY_CACHE_INVALIDATION_SINGLETON_ACTOR,
					ImmutableSet.<String>builder().add(DistributedServicesPropertyBean.ApplicationNames.DataImportManager.toString()).build(), 
					Props.create(SecurityCacheInvalidationSingletonActor.class));
				
		assertTrue("Created singleton actor", oTestMonitor.isPresent());
		Thread.sleep(1000L); // (wait a second or so for it to start up)
		System.out.println("Sending messages");
		
		// send it a couple of pings
		ActorRef testMonitor = oTestMonitor.get(); 
		Thread.sleep(1000L); // (wait a second or so for it to start up)
		*/
	}
	
	@After
	public void cleanupTest() {
		_logger.info("Shutting down actor context");
		_actor_context.onTestComplete();
	}

}
