/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.entrypoint;

import org.apache.flink.runtime.entrypoint.parser.ParserResultFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;

import javax.annotation.Nonnull;

import java.util.Properties;

import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.CONFIG_DIR_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.DYNAMIC_PROPERTY_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.EXECUTION_MODE_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.HOST_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.REST_PORT_OPTION;

/**
 * Parser factory for {@link EntrypointClusterConfiguration}.
 */
public class EntrypointClusterConfigurationParserFactory implements ParserResultFactory<EntrypointClusterConfiguration> {

	@Override
	public Options getOptions() {
		final Options options = new Options();
		options.addOption(CONFIG_DIR_OPTION);
		options.addOption(REST_PORT_OPTION);
		options.addOption(DYNAMIC_PROPERTY_OPTION);
		options.addOption(HOST_OPTION);
		options.addOption(EXECUTION_MODE_OPTION);

		return options;
	}

	@Override
	public EntrypointClusterConfiguration createResult(@Nonnull CommandLine commandLine) {

		// TODO_MA 注释： 解析 --configDir  -c
		final String configDir = commandLine.getOptionValue(CONFIG_DIR_OPTION.getOpt());

		// TODO_MA 注释： 解析程序的 -Dkey-value参数
		final Properties dynamicProperties = commandLine.getOptionProperties(DYNAMIC_PROPERTY_OPTION.getOpt());

		// TODO_MA 注释： 解析 --webui-port -r
		final String restPortStr = commandLine.getOptionValue(REST_PORT_OPTION.getOpt(), "-1");
		final int restPort = Integer.parseInt(restPortStr);

		// TODO_MA 注释： 解析 --host -h
		final String hostname = commandLine.getOptionValue(HOST_OPTION.getOpt());

		/*************************************************
		 *
		 *  注释： 返回一个 EntrypointClusterConfiguration 对象
		 */
		return new EntrypointClusterConfiguration(
			configDir,
			dynamicProperties,
			commandLine.getArgs(),
			hostname,
			restPort);
	}
}
