/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.GNOME.Accessibility;

import javax.accessibility.*;
import java.lang.ref.WeakReference;

public class AtkTable {

	WeakReference<AccessibleContext> _ac;
	WeakReference<AccessibleTable> _acc_table;

	public AtkTable (AccessibleContext ac) {
		this._ac = new WeakReference<AccessibleContext>(ac);
		this._acc_table = new WeakReference<AccessibleTable>(ac.getAccessibleTable());
	}

	public static AtkTable createAtkTable(AccessibleContext ac){
		return AtkUtil.invokeInSwing ( () -> { return new AtkTable(ac); }, null);
	}

	public AccessibleContext ref_at (int row, int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			Accessible accessible = acc_table.getAccessibleAt(row, column);
			if (accessible != null)
				return accessible.getAccessibleContext();
			return null;
		}, null);
	}

	public int get_index_at (int row, int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return -1;

		return AtkUtil.invokeInSwing ( () -> {
			if (acc_table instanceof AccessibleExtendedTable)
				return ( (AccessibleExtendedTable) acc_table).getAccessibleIndex(row, column);
			Accessible child = acc_table.getAccessibleAt(row, column);
			if (child == null)
				return -1;
			AccessibleContext child_ac = child.getAccessibleContext();
			if (child_ac == null)
				return -1;
			return child_ac.getAccessibleIndexInParent();
		}, -1);
	}

	public int get_column_at_index (int index) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return -1;

		return AtkUtil.invokeInSwing ( () -> {
			int column = -1;
			if (acc_table instanceof AccessibleExtendedTable)
				column = ( (AccessibleExtendedTable) acc_table).getAccessibleColumn(index);
			return column;
		}, -1);
	}

	public int get_row_at_index (int index) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return -1;

		return AtkUtil.invokeInSwing ( () -> {
			int row = -1;
			if (acc_table instanceof AccessibleExtendedTable)
				row = ( (AccessibleExtendedTable) acc_table).getAccessibleRow(index);
			return row;
		}, -1);
	}

	public int get_n_columns () {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getAccessibleColumnCount(); }, 0);
	}

	public int get_n_rows () {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getAccessibleRowCount(); }, 0);
	}

	public int get_column_extent_at (int row, int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getAccessibleColumnExtentAt(row, column); }, 0);
	}

	public int get_row_extent_at (int row, int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return 0;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getAccessibleRowExtentAt(row, column); }, 0);
	}

	public AccessibleContext get_caption () {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			Accessible accessible = acc_table.getAccessibleCaption();
			if (accessible != null)
				return accessible.getAccessibleContext();
			return null;
		}, null);
	}

	/**
	 *
	 * @param a an Accessible object
	 */
	public void setCaption(Accessible a) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return;

		AtkUtil.invokeInSwing( () -> { acc_table.setAccessibleCaption(a); });
	}

	public String get_column_description (int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return "";

		return AtkUtil.invokeInSwing ( () -> {
			Accessible accessible = acc_table.getAccessibleColumnDescription(column);
			if (accessible != null) {
				AccessibleContext ac = accessible.getAccessibleContext();
				if (ac != null)
					return ac.getAccessibleDescription();
			}
			return "";
		}, "");
	}

	/**
 	*
 	* @param column an int representing a column in table
 	* @param description a String object representing the description text to set for the
 	*                    specified column of the table
 	*/
	public void setColumnDescription(int column, String description) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return;

		AtkUtil.invokeInSwing( () -> {
			Accessible accessible = acc_table.getAccessibleColumnDescription(column);
			if (accessible != null && description.equals(accessible.toString()))
				acc_table.setAccessibleColumnDescription(column, accessible);
		});
	}

	public String get_row_description (int row) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return "";

		return AtkUtil.invokeInSwing ( () -> {
			Accessible accessible = acc_table.getAccessibleRowDescription(row);
			if (accessible != null) {
				AccessibleContext ac = accessible.getAccessibleContext();
				if (ac != null)
					return ac.getAccessibleDescription();
			}
			return "";
		}, "");
	}

	/**
 	*
	* @param row an int representing a row in table
	* @param description a String object representing the description text to set for the
	*                    specified row of the table
	*/
	public void setRowDescription(int row, String description) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return;

		AtkUtil.invokeInSwing( () -> {
			Accessible accessible = acc_table.getAccessibleRowDescription(row);
			if (accessible != null && description.equals(accessible.toString()))
				acc_table.setAccessibleRowDescription(row, accessible);
		});
	}

	public AccessibleContext get_column_header (int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			AccessibleTable accessibleTable = acc_table.getAccessibleColumnHeader();
			if (accessibleTable != null) {
				Accessible accessible = accessibleTable.getAccessibleAt(0, column);
				if (accessible != null)
					return accessible.getAccessibleContext();
			}
			return null;
		}, null);
	}

	public AccessibleContext get_row_header (int row) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			AccessibleTable accessibleTable = acc_table.getAccessibleRowHeader();
			if (accessibleTable != null) {
				Accessible accessible = accessibleTable.getAccessibleAt(row, 0);
				if (accessible != null)
					return accessible.getAccessibleContext();
			}
			return null;
		}, null);
	}

	public AccessibleContext get_summary () {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return null;

		return AtkUtil.invokeInSwing ( () -> {
			Accessible accessible = acc_table.getAccessibleSummary();
			if (accessible != null)
				return accessible.getAccessibleContext();
			return null;
		}, null);
	}

	/**
	 *
	 * @param a the Accessible object to set summary for
	 */
	public void setSummary(Accessible a) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return;

		AtkUtil.invokeInSwing( () -> { acc_table.setAccessibleSummary(a); });
	}

	public int[] get_selected_columns () {
		int[] d = new int[0];
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return d;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getSelectedAccessibleColumns(); }, d);
	}

	public int[] get_selected_rows () {
		int[] d = new int[0];
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return d;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.getSelectedAccessibleRows(); }, d);
	}

	public boolean is_column_selected (int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return false;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.isAccessibleColumnSelected(column); }, false);
	}

	public boolean is_row_selected (int row) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return false;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.isAccessibleRowSelected(row); }, false);
	}

	public boolean is_selected (int row, int column) {
		AccessibleTable acc_table = _acc_table.get();
		if (acc_table == null)
			return false;

		return AtkUtil.invokeInSwing ( () -> { return acc_table.isAccessibleSelected(row, column); } ,false);
	}
}
