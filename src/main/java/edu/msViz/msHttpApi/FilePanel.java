package edu.msViz.msHttpApi;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

import edu.msViz.mzMD.MzMD;
import edu.msViz.base.ImportState;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Panel to be displayed on StartFrame
 */
class FilePanel extends JPanel {

    private static final Logger LOGGER = Logger.getLogger(FilePanel.class.getName());
    private static final String NO_FILE_TEXT = "No file open";

    // frame to display panel on
    private StartFrame frame;

    // Button texts
    private static final String OPEN_TEXT = "Open...";
    private static final String SAVE_TEXT = "Save As...";
    private static final String CLOSE_TEXT = "Close";

    // panel's GUI components
    private JButton openButton;
    private JButton saveButton;
    private JButton closeButton;
    private JLabel fileLabel;

    private final JFileChooser fileChooser;
    FileNameExtensionFilter openFilter = new FileNameExtensionFilter("Mass Spectrometry Data File", "mzML", "mzMD", "csv");
    FileNameExtensionFilter saveFilter = new FileNameExtensionFilter("mzMD file", "mzTree");

    /**
     * Default constructor
     * Configures panel's components
     */
    public FilePanel(StartFrame frame) {
        this.frame = frame;

        setLayout(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.weightx = 1;

        // add the open, save, and close buttons
        c.gridy = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        openButton = new JButton(OPEN_TEXT);
        openButton.setMnemonic('O');
        openButton.addActionListener(this::openClicked);
        c.gridx = 0;
        this.add(openButton, c);

        saveButton = new JButton(SAVE_TEXT);
        saveButton.setMnemonic('A');
        saveButton.addActionListener(this::saveClicked);
        c.gridx = 1;
        this.add(saveButton, c);

        closeButton = new JButton(CLOSE_TEXT);
        closeButton.setMnemonic('C');
        closeButton.addActionListener(this::closeClicked);
        c.gridx = 2;
        this.add(closeButton, c);

        // add the file name display
        fileLabel = new JLabel(NO_FILE_TEXT);
        fileLabel.setPreferredSize(new Dimension(100, 30));
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 4;
        this.add(fileLabel, c);

        // create the file chooser and set its filter to supported mass spec file types
        fileChooser = new JFileChooser();
    }

    public void setFileOpenState(boolean state) {
        saveButton.setEnabled(state);
        closeButton.setEnabled(state);
    }

    private void openClicked(ActionEvent e) {
    	printInfo();
        // if user clicks "OK" (chose a file using the dialog):
        fileChooser.resetChoosableFileFilters();
        fileChooser.setFileFilter(openFilter);
//        Path suggestedFilePath = Paths.get("F:\\project\\example_data\\small.pwiz.1.1.mzML");
//        fileChooser.setSelectedFile(suggestedFilePath.toFile());
        int fileChooserResult = fileChooser.showOpenDialog(frame);
        if(fileChooserResult == JFileChooser.APPROVE_OPTION) {
            String filePath = fileChooser.getSelectedFile().getPath();
            System.out.println(filePath);
            // disconnect and drop the previous mzTree
            closeClicked(e);
            frame.mzMD = new MzMD();

            // when the mzTree wants a conversion destination (when opening an mzML file for example):
            // try to get a save-as destination from the user. Fall back to the suggested default path
            // if there is an error or the user cancels.
            frame.mzMD.setConvertDestinationProvider(suggested -> {
            	printInfo();
            	System.out.println(suggested);
                final Path[] userPath = new Path[1];
                try {
                    SwingUtilities.invokeAndWait(() -> userPath[0] = requestUserSavePath(suggested));
                } catch (InterruptedException|InvocationTargetException ex) {
                    userPath[0] = null;
                }
                if (userPath[0] == null) {
                    throw new Exception("User canceled file conversion.");
                }
                return userPath[0];
            });

            printInfo();
            // process mzTree on new thread so that UI thread remains responsive
            Thread mzTreeThread = new Thread(() -> {
                long start = System.currentTimeMillis();
                printInfo();
                try
                {
                    // attempt to create mzTree
                    frame.dataServer.setMzTree(frame.mzMD);
                    printInfo();
                    frame.mzMD.load(filePath);
                    printInfo();

                    LOGGER.log(Level.INFO, "MzMD load time: " + (System.currentTimeMillis() - start));
                    printInfo();

                    SwingUtilities.invokeLater(this::updateFileState);
                    printInfo();
                }
                catch (Exception ex)
                {
                    frame.mzMD = null;

                    LOGGER.log(Level.WARNING, "Could not open requested file", ex);
                }
            });
            printInfo();
            
            // whenever the import state changes, update the status label
            // the <html> tag and escapeHtml are used so it word-wraps in the JLabel
            frame.mzMD.getImportState().addObserver((o, arg) -> {
                final String status = ((ImportState)o).getStatusString();
                SwingUtilities.invokeLater(() -> frame.setStatusText(status));
            });

            mzTreeThread.start();
        }
    }

    private void updateFileState() {
        if (frame.mzMD == null || frame.mzMD.getLoadStatus() != ImportState.ImportStatus.READY) {
            frame.setFileOpenState(false);
            fileLabel.setText(NO_FILE_TEXT);
            fileLabel.setToolTipText(null);
        } else {
            frame.setFileOpenState(true);
            String resultFilePath = frame.mzMD.dataStorage.getFilePath();
            fileLabel.setText("File: " + Paths.get(resultFilePath).getFileName());
            fileLabel.setToolTipText(resultFilePath);
        }

    }

    private void saveClicked(ActionEvent e) {
        // ensure model ready to save
        if(frame.mzMD == null || frame.mzMD.getLoadStatus() != ImportState.ImportStatus.READY){
            return;
        }

        // suggest a filename based on the date and time
        String suggestedFilename = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss").format(new Date());
        Path suggestedFilePath = Paths.get(frame.mzMD.dataStorage.getFilePath()).resolveSibling(suggestedFilename);

        Path targetFilepath = requestUserSavePath(suggestedFilePath);
        if (targetFilepath == null) {
            return;
        }

        try {
            // disconnect HTTP server while saving, reconnect after copied
            frame.dataServer.setMzTree(null);
            frame.mzMD.saveAs(targetFilepath);
            frame.dataServer.setMzTree(frame.mzMD);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage());
            LOGGER.log(Level.WARNING, "Could not copy mzTree file", ex);
        } finally {
            updateFileState();
        }
    }

    // shows a save dialog to ask for a path. null indicates error or cancellation
    private Path requestUserSavePath(Path suggestedFilePath) {
        System.out.println(Thread.currentThread().getStackTrace()[1].getMethodName());
        // set up the file dialog
        fileChooser.resetChoosableFileFilters();
        fileChooser.setFileFilter(saveFilter);
        fileChooser.setSelectedFile(suggestedFilePath.toFile());

        // prompt to choose a file
        if (fileChooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        // make sure it doesn't already exist and has the mzTree extension
        File targetFile = fileChooser.getSelectedFile();
        if (targetFile.exists()) {
            JOptionPane.showMessageDialog(frame, "The file already exists.");
            return null;
        }
        if (!targetFile.getName().endsWith(".mzMD")) {
            targetFile = new File(targetFile.getAbsolutePath() + ".mzMD");
        }

        // return normalized version of the user's chosen path
        return targetFile.toPath().normalize();
    }

    private void closeClicked(ActionEvent e) {
        frame.dataServer.setMzTree(null);
        if (frame.mzMD != null) {
            frame.mzMD.close();
            frame.mzMD = null;
        }

        updateFileState();
    }
    
    private void printInfo() {
    	System.out.println(Thread.currentThread().getStackTrace()[2].getMethodName()+", line "+Thread.currentThread().getStackTrace()[2].getLineNumber());
    }
}
