/*
 *     Copyright (C) 2025 proferabg
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


import com.velocitypowered.api.proxy.Player;

public interface ProtocolVersionProvider {

    boolean has18OrLater(Player player);

    boolean has113OrLater(Player player);
    
    boolean has119OrLater(Player player);

    boolean is18(Player player);

    String getVersion(Player player);

    boolean has1193OrLater(Player player);

    boolean has1203OrLater(Player player);

    boolean has1214OrLater(Player player);
}
