package com.sshtools.forker.client.impl;

import java.io.IOException;

import com.sshtools.forker.client.AbstractForkerProcess;
import com.sshtools.forker.client.ForkerBuilder;
import com.sshtools.forker.client.ForkerProcessFactory;
import com.sshtools.forker.common.IO;

/**
 * Create a {@link LocalProcess} if requested to explicity do so.
 *
 */
public class DefaultProcessFactory implements ForkerProcessFactory {

	@Override
	public AbstractForkerProcess createProcess(ForkerBuilder builder) throws IOException {
		if (builder.io() == IO.DEFAULT) {
			return new LocalProcess(builder);
		}
		return null;
	}

}
