package lejos.ev3.tools;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import javax.swing.table.AbstractTableModel;

import lejos.remote.ev3.RMIMenu;
import lejos.remote.nxt.FileInfo;

/**
 * Swing Table Model for manipulating EV3 files.
 * 
 * @author Lawrie Griffiths
 */
public class ExtendedFileModel extends AbstractTableModel {
	private static final long serialVersionUID = -6173853132812064498L;
	private static final String[] columnNames = { "File", "Size", "Delete" };
	private static final int NUM_COLUMNS = 3;
	public static final int MAX_FILES = 30;
	public static final int COL_NAME = 0;
	public static final int COL_SIZE = 1;
	public static final int COL_DELETE = 2;

	private ArrayList<Boolean> delete = new ArrayList<Boolean>();
	private ArrayList<FileInfo> files = new ArrayList<FileInfo>();
	
	private RMIMenu menu;
	private String[] fileNames;
	private boolean programs;
	private String directory;
	

	/**
	 * Fetch files from the EV3 and create the model
	 */
	public ExtendedFileModel(RMIMenu menu, String directory, boolean programs) {
		this.menu = menu;
		this.directory = directory;
		this.programs = programs;
	}

	/**
	 * Delete a file on the NXT and update the model
	 * 
	 * @param fileName
	 *            the file to delete
	 * @param row
	 *            the row in the file model
	 * 
	 * @throws IOException
	 */
	public void delete(String fileName, int row) throws IOException {
		files.remove(row);
		delete.remove(row);
		this.fireTableRowsDeleted(row, row);
	}

	/**
	 * Get the number of rows in the model
	 * 
	 * @return the number of files in the model
	 */
	public int getRowCount() {
		return files.size();
	}

	/**
	 * Get the number of columns in the mode
	 * 
	 * @return the column count
	 */
	public int getColumnCount() {
		return NUM_COLUMNS;
	}

	/**
	 * Get the object at the specified location
	 * 
	 * @return the object at the specified location
	 */
	public Object getValueAt(int row, int column) {
		FileInfo f = files.get(row);
		switch (column) {
		case COL_NAME:
			return f.fileName;
		case COL_SIZE:
			return Integer.valueOf(f.fileSize);
		case COL_DELETE:
			return delete.get(row);
		default:
			throw new RuntimeException("unknown column");
		}
	}

	/**
	 * Set the value of a cell
	 */
	public void setValueAt(Object value, int row, int column) {
		if (column != COL_DELETE)
			throw new RuntimeException("invalid column");

		delete.set(row, (Boolean) value);
	}

	/**
	 * Get the name of a column
	 * 
	 * @return the column name
	 */
	public String getColumnName(int column) {
		return columnNames[column];
	}

	/**
	 * Get the class of a specific column
	 * 
	 * @return the class of the column
	 */
	public Class<?> getColumnClass(int column) {
		switch (column) {
		case COL_NAME:
			return String.class;
		case COL_SIZE:
			return Integer.class;
		case COL_DELETE:
			return Boolean.class;
		default:
			throw new RuntimeException("unknown column");
		}
	}

	/**
	 * Check if a cell is editable
	 * 
	 * @return true iff the cell is editable
	 */
	public boolean isCellEditable(int row, int column) {
		return (column == COL_DELETE);
	}

	/**
	 * Fetch the files from the NXT
	 * 
	 * @return null for success or the error message
	 */
	public String fetchFiles() {
		long[] sizes;
		
		try {
			fileNames = (programs ? menu.getProgramNames() : menu.getSampleNames());
			sizes = new long[fileNames.length];
			for(int i=0;i<sizes.length;i++) {
				sizes[i] = menu.getFileSize(directory + fileNames[i]);
			}

		} catch (RemoteException e1) {
			e1.printStackTrace();
			return null;
		}
		files.clear();
		delete.clear();
		FileInfo f;
		for(int i=0;i<fileNames.length;i++) {
			f = new FileInfo(fileNames[i]);
			f.fileSize = (int) sizes[i];
			files.add(f);
			delete.add(Boolean.FALSE);
		}

		Collections.sort(files, new Comparator<FileInfo>() {
			public int compare(FileInfo o1, FileInfo o2) {
				return o1.fileName.compareTo(o2.fileName);
			}
		});

		this.fireTableDataChanged();
		return null;
	}

	/**
	 * Get the FileInfo object for a specific file
	 * 
	 * @param i
	 *            the row number of the file
	 * @return the FileInfo object
	 */
	public FileInfo getFile(int i) {
		return files.get(i);
	}

	/**
	 * Return the number of files
	 * 
	 * @return the number of files
	 */
	public int numFiles() {
		return files.size();
	}

	/**
	 * Return the row for a given file
	 * 
	 * @param fileName
	 *            the filename
	 * @return the row of -1 if not found
	 */
	public int getRow(String fileName) {
		int len = files.size();
		for (int i = 0; i < len; i++)
			if (fileName.equals(files.get(i).fileName))
				return i;

		return -1;
	}
}
