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
package com.ikanow.aleph2.data_model.utils;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.Test;

import scala.Tuple2;

import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsAccessContext;

import fj.data.Either;

public class TestAnalyticsUtils {

	public interface StringAnalyticsAccessContext extends IAnalyticsAccessContext<String> {}
		
	@Test
	public void test_injectedImplementation() {
		
		assertEquals(String.class, AnalyticsUtils.getTypeName(StringAnalyticsAccessContext.class));
		
		IAnalyticsAccessContext<String> anon = new IAnalyticsAccessContext<String>() {
			@Override
			public Either<String, Class<String>> getAccessService() {
				return Either.left("test");
			}
			@Override
			public Optional<Map<String, Object>> getAccessConfig() {
				return Optional.of(Collections.emptyMap());
			}			
		};	
		final StringAnalyticsAccessContext test = AnalyticsUtils.injectImplementation(StringAnalyticsAccessContext.class, anon);
		
		assertEquals(Either.left("test"), test.getAccessService());
		assertEquals(Collections.emptyMap(), test.getAccessConfig().get());
		assertEquals("service_name=String options=", test.describe());
	}
	
	public static class TestClass {
		TestClass(String s) { _s = s; }
		String _s;
	}
	
	public interface MyFunction extends Function<String, TestClass> {} 
	
	
	@Test
	public void test_functionInjection() {
		
		// Check if function type getting works
		
		{
			final Optional<Tuple2<Class<?>, Class<?>>> ret_val = AnalyticsUtils.getFunctionTypes(MyFunction.class);
			assertTrue(ret_val.isPresent());
			assertEquals(String.class, ret_val.get()._1());
			assertEquals(TestClass.class, ret_val.get()._2());
		}
	}
}
