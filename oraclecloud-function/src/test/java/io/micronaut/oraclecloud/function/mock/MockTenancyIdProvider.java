package io.micronaut.oraclecloud.function.mock;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.oraclecloud.core.TenancyIdProvider;

import javax.inject.Singleton;

@Context
@Singleton
@Replaces(TenancyIdProvider.class)
public class MockTenancyIdProvider implements TenancyIdProvider {

    @Nullable
    @Override
    public String getTenancyId() {
        return MockData.tenancyId;
    }
}