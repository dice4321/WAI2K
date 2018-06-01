/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.views.tabs.profile

import javafx.scene.control.TreeView
import javafx.scene.layout.HBox
import org.controlsfx.control.MasterDetailPane
import tornadofx.*

class ProfileTabView: View() {
    override val root: HBox by fxml("/views/tabs/profile-tab.fxml")
    private val profileTreeView: TreeView<String> by fxid()
    private val profilePane: MasterDetailPane by fxid()

    init {
        title = "Profile"
    }
}