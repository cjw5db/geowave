/*******************************************************************************
 * Copyright (c) 2013-2017 Contributors to the Eclipse Foundation
 * 
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License,
 * Version 2.0 which accompanies this distribution and is available at
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/
package mil.nga.giat.geowave.datastore.accumulo.cli.config;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mil.nga.giat.geowave.core.cli.converters.PasswordConverter;
import mil.nga.giat.geowave.core.store.DataStoreOptions;
import mil.nga.giat.geowave.core.store.StoreFactoryFamilySpi;
import mil.nga.giat.geowave.core.store.StoreFactoryOptions;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloStoreFactoryFamily;

/**
 * Default, required options needed in order to execute any command for
 * Accumulo.
 */
public class AccumuloRequiredOptions extends
		StoreFactoryOptions
{

	public static final String ZOOKEEPER_CONFIG_KEY = "zookeeper";
	public static final String INSTANCE_CONFIG_KEY = "instance";
	public static final String USER_CONFIG_KEY = "user";
	// HP Fortify "Hardcoded Password" false positive
	// This is a password label, not a password
	public static final String PASSWORD_CONFIG_KEY = "password";

	@Parameter(names = {
		"-z",
		"--" + ZOOKEEPER_CONFIG_KEY
	}, description = "A comma-separated list of zookeeper servers that an Accumulo instance is using", required = true)
	private String zookeeper;

	@Parameter(names = {
		"-i",
		"--" + INSTANCE_CONFIG_KEY
	}, description = "The Accumulo instance ID", required = true)
	private String instance;

	@Parameter(names = {
		"-u",
		"--" + USER_CONFIG_KEY
	}, description = "A valid Accumulo user ID", required = true)
	private String user;

	@Parameter(names = {
		"-p",
		"--" + PASSWORD_CONFIG_KEY
	}, description = "The password for the user. " + PasswordConverter.DEFAULT_PASSWORD_DESCRIPTION, descriptionKey = "accumulo.pass.label", converter = PasswordConverter.class)
	private String password;

	@ParametersDelegate
	private AccumuloOptions additionalOptions = new AccumuloOptions();

	public String getZookeeper() {
		return zookeeper;
	}

	public void setZookeeper(
			final String zookeeper ) {
		this.zookeeper = zookeeper;
	}

	public String getInstance() {
		return instance;
	}

	public void setInstance(
			final String instance ) {
		this.instance = instance;
	}

	public String getUser() {
		return user;
	}

	public void setUser(
			final String user ) {
		this.user = user;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(
			final String password ) {
		this.password = password;
	}

	public void setStoreOptions(
			final AccumuloOptions additionalOptions ) {
		this.additionalOptions = additionalOptions;
	}

	@Override
	public StoreFactoryFamilySpi getStoreFactory() {
		return new AccumuloStoreFactoryFamily();
	}

	@Override
	public DataStoreOptions getStoreOptions() {
		return additionalOptions;
	}
}
