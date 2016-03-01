/*
    This file is part of Keratoconus Assistant.

    Keratoconus Assistant is free software: you can redistribute it 
    and/or modify it under the terms of the GNU General Public License 
    as published by the Free Software Foundation, either version 3 of 
    the License, or (at your option) any later version.

    Keratoconus Assistant is distributed in the hope that it will be 
    useful, but WITHOUT ANY WARRANTY; without even the implied warranty 
    of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Keratoconus Assistant.  If not, see 
    <http://www.gnu.org/licenses/>.
 */

package be.uza.keratoconus.model.api;

import java.util.List;

import aQute.bnd.annotation.ProviderType;

/**
 * Service which is used to interact with the WEKA-based model.
 * 
 * @author Chris Gray
 *
 */
@ProviderType
public interface AvailableModelsService {

	/**
	 * Get the names of all available models in this installation.
	 */
	List<String> getAvailableModelNames();

	/**
	 * Get the human-readable description of an available models given its name.
	 */
	String getModelDescription(String name);

	/**
	 * Select the model to be used. This should be called before any of the
	 * methods {@link #getCommonFields()}, {@link #getKeyFields()}
	 * are called.
	 * 
	 * @param name
	 *            The name of the model (must be one of those returned by
	 *            {@link #getAvailableModelNames()}).
	 * @throws Exception 
	 */
	void selectModel(String name) throws Exception;

	/**
	 * Get the name of the currently selected model.
	 * @return
	 */
	String getSelectedModelName();

}
