/*
 * Copyright (c) 2021.
 *
 * This file is part of DiscoAPI.
 *
 *     DiscoAPI is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 2 of the License, or
 *     (at your option) any later version.
 *
 *     DiscoAPI is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DiscoAPI.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.foojay.api.scopes;

import java.util.Arrays;
import java.util.List;


public enum DownloadScope implements Scope {
    DIRECTLY("Directly downloadable", "directly_downloadable"),
    NOT_DIRECTLY("Not directly downloadable", "not_directly_downloadable");

    private final String name;
    private final String token;


    DownloadScope(final String name, final String token) {
        this.name  = name;
        this.token = token;
    }


    @Override public String getName() { return name; }

    @Override public String getToken() { return token; }


    public static Scope fromToken(final String token) {
        for (Scope scope : DownloadScope.values()) {
            if (scope.getToken().equals(token)) { return scope; }
        }
        return Scope.NOT_FOUND;
    }

    public static List<DownloadScope> getAsList() { return Arrays.asList(values()); }
}
