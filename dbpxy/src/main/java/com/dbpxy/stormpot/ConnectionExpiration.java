package com.dbpxy.stormpot;

import com.dbpxy.jdbc.ConnectionProxy;
import lombok.RequiredArgsConstructor;
import stormpot.Expiration;
import stormpot.SlotInfo;

import java.sql.SQLException;

@RequiredArgsConstructor
public class ConnectionExpiration implements Expiration<ConnectionProxy> {
    private final long maxAgeInMs;

    @Override
    public boolean hasExpired(final SlotInfo<? extends ConnectionProxy> info) throws Exception {
        try {
            final ConnectionProxy proxy = info.getPoolable();
            return info.getAgeMillis() > maxAgeInMs || !proxy.getConnection().isValid(1);
        } catch (final SQLException e) {
            return true;
        }
    }
}
