/*
 *     Copyright (C) 2020 Florian Stober
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package codecrafter47.bungeetablistplus.version;

import net.md_5.bungee.api.connection.ProxiedPlayer;

public interface ProtocolVersionProvider {

    boolean has18OrLater(ProxiedPlayer player);

    boolean has113OrLater(ProxiedPlayer player);
    
    boolean has119OrLater(ProxiedPlayer player);

    boolean is18(ProxiedPlayer player);

    String getVersion(ProxiedPlayer player);

    boolean has1193OrLater(ProxiedPlayer player);

    boolean has1214OrLater(ProxiedPlayer player);
}
