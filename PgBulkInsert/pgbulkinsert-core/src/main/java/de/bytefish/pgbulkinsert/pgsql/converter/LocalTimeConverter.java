// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package de.bytefish.pgbulkinsert.pgsql.converter;

import java.time.LocalTime;

public class LocalTimeConverter implements IValueConverter<LocalTime, Long> {

    @Override
    public Long convert(final LocalTime time) {
        return time.toNanoOfDay() / 1000L;
    }

}
