/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2015 phrack
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

package com.shootoff;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Camera;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.plugins.TextToSpeech;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class Main extends Application {
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	private final String RESOURCES_METADATA_NAME = "shootoff-writable-resources.xml";
	private final String RESOURCES_JAR_NAME = "shootoff-writable-resources.jar";
	private File resourcesMetadataFile;
	private File resourcesJARFile;
	private Stage primaryStage;
	
	protected static class ResourcesInfo {
		private String version;
		private long fileSize;
		private String xml;
		
		public ResourcesInfo(String version, long fileSize, String xml) {
			this.version = version;
			this.fileSize = fileSize;
			this.xml = xml;
		}
		
		public String getVersion() {
			return version;
		}
		
		public long getFileSize() {
			return fileSize;
		}
		
		public String getXML() {
			return xml;
		}
	}
	
	private Optional<String> parseField(String metadataXML, String fieldName) {
		String tagName = "<resources";
		int tagStart = metadataXML.indexOf(tagName);
		
		if (tagStart == -1) {
			logger.error("Couldn't parse resources tag from resources metadata");
			tryRunningShootOFF();
			return Optional.empty();	
		}
		
		tagStart += tagName.length();
		
		fieldName += "=\"";
		int dataStart = metadataXML.indexOf(fieldName, tagStart);
		
		if (dataStart == -1) {
			logger.error(String.format("Couldn't parse %s field from resources metadata", fieldName));
			tryRunningShootOFF();
			return Optional.empty();
		}
		
		dataStart += fieldName.length();
		
		int dataEnd = metadataXML.indexOf("\"", dataStart);
		
		return Optional.of(metadataXML.substring(dataStart, dataEnd));
	}
	
	protected Optional<ResourcesInfo> deserializeMetadataXML(String metadataXML) {
		Optional<String> version = parseField(metadataXML, "version");
		Optional<String> fileSize = parseField(metadataXML, "fileSize");
		
		if (version.isPresent() && fileSize.isPresent()) {
			return Optional.of(new ResourcesInfo(version.get(), Long.parseLong(fileSize.get()), metadataXML));
		}
		
		return Optional.empty();
	}
	
	private Optional<ResourcesInfo> getWebstartResourcesInfo(File metadataFile) {
		if (!metadataFile.exists()) {
			logger.error("Local metadata file unavailable");
			return Optional.empty();
		}
		
		try {
			String metadataXML = new String(Files.readAllBytes(metadataFile.toPath()), "UTF-8");
			return deserializeMetadataXML(metadataXML);
		} catch (IOException e) {
			logger.error("Error reading metadata XML for JNLP", e);
		}
		
		return Optional.empty();
	}
	
	private Optional<ResourcesInfo> getWebstartResourcesInfo(String metadataAddress) {
		HttpURLConnection connection = null;
		InputStream stream = null;
		
		try {
			connection = (HttpURLConnection)new URL(metadataAddress).openConnection();
			stream = connection.getInputStream();
		} catch (UnknownHostException e) {
			logger.error("Could not connect to remote host " + e.getMessage() + " to download writable resources.", e);
			tryRunningShootOFF();
			return Optional.empty();
		} catch (IOException e) {
			if (connection != null) connection.disconnect();

			logger.error("Error download writable resources file", e);
			tryRunningShootOFF();
			return Optional.empty();
		}
		
        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
        StringBuilder metadataXML = new StringBuilder();
        
        try {
	        String line;
	        while ((line = br.readLine()) != null) {
	        	if (metadataXML.length() > 0) metadataXML.append("\n");
	            metadataXML.append(line);
	        }
        } catch (IOException e) {
			connection.disconnect();

			logger.error("Failed to read resources metadata", e);
			tryRunningShootOFF();
			return Optional.empty();
        } finally {
        	try {
				br.close();
			} catch (IOException e) {
				logger.error("Error closing reader opened to process resource metadata", e);
			}
        }
		
		connection.disconnect();
		
		return deserializeMetadataXML(metadataXML.toString());
	}
	
	/**
	 * Writable resources (e.g. shootoff.properties, sounds, targets, etc.) cannot be included in 
	 * JAR files for a Webstart applications, thus we download them from a remote URL and extract 
	 * them locally if necessary.
	 * 
	 * Downloads the file at fileAddress with the assumption that it is a JAR containing
	 * writable resources. If there is an existing JAR with writable resources we
	 * only do the download if the file sizes are different. 
	 * 
	 * @param fileAddress	the url (e.g. http://example.com/file.jar) that contains ShootOFF's writable resources
	 */
	private void downloadWebstartResources(ResourcesInfo ri, String fileAddress) {
		HttpURLConnection connection = null;
		InputStream stream = null;
		
		try {
			connection = (HttpURLConnection)new URL(fileAddress).openConnection();
			stream = connection.getInputStream();
		} catch (UnknownHostException e) {
			logger.error("Could not connect to remote host " + e.getMessage() + " to download writable resources.", e);
			tryRunningShootOFF();
			return;
		} catch (IOException e) {
			if (connection != null) connection.disconnect();

			logger.error("Failed to get stream to download writable resources file", e);
			tryRunningShootOFF();
			return;
		}
		
		long remoteFileLength = ri.getFileSize();

		if (remoteFileLength == 0) {
			logger.error("Remote writable resources file query returned 0 len.");
			connection.disconnect();
			tryRunningShootOFF();
			return;
		}
		
        final InputStream remoteStream = stream;
        Task<Boolean> task = new Task<Boolean>() {
            @Override
            public Boolean call() throws InterruptedException {
    			BufferedInputStream bufferedInputStream = new BufferedInputStream(remoteStream);
    			FileOutputStream fileOutputStream = null;
    			
    			try {
    				fileOutputStream = new FileOutputStream(resourcesJARFile);
	    	
    				long totalDownloaded = 0;
	    			int count;
	    			byte buffer[] = new byte[1024];
	    	
	    			while ((count = bufferedInputStream.read(buffer, 0, buffer.length)) != -1) {
	    				fileOutputStream.write(buffer, 0, count);
	    				totalDownloaded += count;
	    				updateProgress(((double)totalDownloaded / (double)remoteFileLength) * 100, 100);
	    			}
	    			
	    			fileOutputStream.close();
	    			
	                updateProgress(100, 100);
    			} catch (IOException e) {
    				if (fileOutputStream != null) {
    					try {
    						fileOutputStream.close();
    					} catch (IOException e1) {
    						e1.printStackTrace();
    					}
    				}
    				
    				logger.error("Failed to download writable resources file", e);
    				return false;
    			}
    			
                return true;
            }
        };
        
        final ProgressDialog progressDialog = new ProgressDialog("Downloading Resources...", 
        		"Downloading required resources (targets, sounds, etc.)...", task);
        final HttpURLConnection con = connection;
        task.setOnSucceeded((value) -> {
        		progressDialog.close();
        		con.disconnect();
        		if (task.getValue()) {
        			try {
        				PrintWriter out = new PrintWriter(resourcesMetadataFile);
        				out.print(ri.getXML());
        				out.close();
        			} catch (IOException e) {
        				logger.error("Could't update metadata file: " + e.getMessage(), e);
        			}
        			
        			extractWebstartResources();
        		} else {
        			tryRunningShootOFF();
        		}
        	});
        
        new Thread(task).start();    
	}
	
	/**
	 * If we could not acquire writable resources for Webstart, see if we have enough
	 * to run anyway.
	 */
	private void tryRunningShootOFF() {
		if (!new File(System.getProperty("shootoff.home") + File.separator + "shootoff.properties").exists()) {
			Alert resourcesAlert = new Alert(AlertType.ERROR);
			resourcesAlert.setTitle("Missing Resources");
			resourcesAlert.setHeaderText("Missing Required Resources!");
			resourcesAlert.setResizable(true);
			resourcesAlert.setContentText("ShootOFF could not acquire the necessary resources to run. Please ensure "
					+ "you have a connection to the Internet and can connect to http://shootoffapp.com and try again.\n\n"
					+ "If you cannot get the browser-launched version of ShootOFF to work, use the standlone version from "
					+ "the website.");
			resourcesAlert.showAndWait();
		} else {
			runShootOFF();
		}
	}

	private void extractWebstartResources() {	
		Task<Boolean> task = new Task<Boolean>() {
			@Override
			protected Boolean call() throws Exception {
				JarFile jar = null;
				
				try {
					jar = new JarFile(resourcesJARFile);
					
					Enumeration<JarEntry> enumEntries = jar.entries();
					int fileCount = 0;
					while (enumEntries.hasMoreElements()) {
						JarEntry entry = (JarEntry)enumEntries.nextElement();
						if (!entry.getName().startsWith("META-INF") && !entry.isDirectory()) fileCount++;
					}
					
					enumEntries = jar.entries();
					int currentCount = 0;
					while (enumEntries.hasMoreElements()) {
					    JarEntry entry = (JarEntry)enumEntries.nextElement();
					    
					    if (entry.getName().startsWith("META-INF")) continue;
					    
					    File f = new File(System.getProperty("shootoff.home") + File.separator + entry.getName());
					    if (entry.isDirectory()) {
					        if (!f.exists() && !f.mkdir()) 
					        	throw new IOException("Failed to make directory while extracting JAR: " + entry.getName());
					    } else {			    	
						    InputStream is = jar.getInputStream(entry);
						    FileOutputStream fos = new FileOutputStream(f);
						    while (is.available() > 0) {
						        fos.write(is.read());
						    }
						    fos.close();
						    is.close();
						    
						    currentCount++;
						    updateProgress(((double)currentCount / (double)fileCount) * 100, 100);
					    }
					}
					
					updateProgress(100, 100);
				} catch (IOException e) {
					logger.error("Error extracting writable resources file for JNLP", e);
					return false;
				} finally {
					try {
						if (jar != null) jar.close();
					} catch (IOException e) {
						logger.error("Error closing writable resources file for JNLP", e);
					}
				}
				
				return true;
			}
		};
		
        final ProgressDialog progressDialog = new ProgressDialog("Extracting Resources...", 
        		"Extracting required resources (targets, sounds, etc.)...", task);
        task.setOnSucceeded((value) -> {
        		progressDialog.close();
        		if (task.getValue()) {
        			runShootOFF();
        		} else {
        			tryRunningShootOFF();
        		}
        	});
        
        new Thread(task).start();    
	}
	
    public static class ProgressDialog {
        private final Stage stage = new Stage();
        private final Label messageLabel = new Label();
        private final ProgressBar pb = new ProgressBar();
        private final ProgressIndicator pin = new ProgressIndicator();

        public ProgressDialog(String dialogTitle, String dialogMessage, final Task<?> task) {
            stage.setTitle(dialogTitle);
            stage.initModality(Modality.APPLICATION_MODAL);

            pb.setProgress(-1F);
            pin.setProgress(-1F);
            
            messageLabel.setText(dialogMessage);

            final HBox hb = new HBox();
            hb.setSpacing(5);
            hb.setAlignment(Pos.CENTER);
            hb.getChildren().addAll(pb, pin);

            pb.prefWidthProperty().bind(hb.widthProperty().subtract(hb.getSpacing() * 6));
            
            BorderPane bp = new BorderPane();
            bp.setTop(messageLabel);
            bp.setBottom(hb);
            
            Scene scene = new Scene(bp);
            
            stage.setScene(scene);
            stage.show();
            
            pb.progressProperty().bind(task.progressProperty());
            pin.progressProperty().bind(task.progressProperty());
        }
        
        public void close() {
        	stage.close();
        }
    }
	
    public void runShootOFF() {
		String[] args = getParameters().getRaw().toArray(new String[getParameters().getRaw().size()]);
		Configuration config;
		try {
			config = new Configuration(System.getProperty("shootoff.home") + File.separator + 
					"shootoff.properties", args);
		} catch (IOException | ConfigurationException e) {
			logger.error("Error fetching ShootOFF configuration", e);
			return;
		}
		
		// This initializes the TTS engine
		TextToSpeech.say("");
		
		try {
			FXMLLoader loader = new FXMLLoader(Main.class.getResource("/com/shootoff/gui/ShootOFF.fxml"));
		    loader.load();   
			
			Scene scene = new Scene(loader.getRoot());
			
			primaryStage.setTitle("ShootOFF");
			primaryStage.setScene(scene);
			((ShootOFFController)loader.getController()).init(config);
			primaryStage.show();
		} catch (IOException e) {
			logger.error("Error loading ShootOFF FXML file", e);
			return;
		}
    }
    
    public static void closeNoCamera() {
		Alert cameraAlert = new Alert(AlertType.ERROR);
		cameraAlert.setTitle("No Webcams");
		cameraAlert.setHeaderText("No Webcams Found!");
		cameraAlert.setResizable(true);
		cameraAlert.setContentText("ShootOFF needs a webcam to function. Now closing...");
		cameraAlert.showAndWait();
		System.exit(-1);
    }
    
	@Override
	public void start(Stage primaryStage) {
		this.primaryStage = primaryStage;
		
		String os = System.getProperty("os.name"); 
		if (os != null && os.equals("Mac OS X") && Camera.getWebcams().isEmpty()) {
			closeNoCamera();
		}
		
		if (System.getProperty("javawebstart.version", null) != null) {
			File shootoffHome = new File(System.getProperty("user.home") + File.separator + ".shootoff");
			
			if (!shootoffHome.exists()) {
				if (!shootoffHome.mkdirs()) {
					Alert homeAlert = new Alert(AlertType.ERROR);
					homeAlert.setTitle("No ShootOFF Home");
					homeAlert.setHeaderText("Missing ShootOFF's Home Directory!");
					homeAlert.setResizable(true);
					homeAlert.setContentText("ShootOFF's home directory " + shootoffHome.getPath() + " "
							+ "does not exist and could not be created. Now closing...");
					homeAlert.showAndWait();
					return;
				}
			}
			
			System.setProperty("shootoff.home", shootoffHome.getAbsolutePath());
			System.setProperty("shootoff.sessions", System.getProperty("shootoff.home") + File.separator + "sessions");
			System.setProperty("shootoff.courses", System.getProperty("shootoff.home") + File.separator + "courses");
			
			resourcesMetadataFile = new File(System.getProperty("shootoff.home") + File.separator + RESOURCES_METADATA_NAME);
			Optional<ResourcesInfo> localRI = getWebstartResourcesInfo(resourcesMetadataFile);
			Optional<ResourcesInfo> remoteRI = getWebstartResourcesInfo("http://shootoffapp.com/jws/" + RESOURCES_METADATA_NAME);
		
			if (!localRI.isPresent() && remoteRI.isPresent()) {
				resourcesJARFile = new File(System.getProperty("shootoff.home") + File.separator + RESOURCES_JAR_NAME);
				downloadWebstartResources(remoteRI.get(), "http://shootoffapp.com/jws/" + RESOURCES_JAR_NAME);
			} else if (localRI.isPresent() && remoteRI.isPresent()) {
				if (!localRI.get().getVersion().equals(remoteRI.get().getVersion())) {
					System.out.println(String.format("Local version: %s, Remote version: %s", 
							localRI.get().getVersion(), remoteRI.get().getVersion()));
					resourcesJARFile = new File(System.getProperty("shootoff.home") + File.separator + RESOURCES_JAR_NAME);
					downloadWebstartResources(remoteRI.get(), "http://shootoffapp.com/jws/" + RESOURCES_JAR_NAME);				
				} else {
					runShootOFF();
				}
			} else {
				System.err.println("Could not locate local or remote resources metadata");
			}
		} else {
			System.setProperty("shootoff.home", System.getProperty("user.dir"));
			System.setProperty("shootoff.sessions", System.getProperty("shootoff.home") + File.separator + "sessions");
			System.setProperty("shootoff.courses", System.getProperty("shootoff.home") + File.separator + "courses");
			runShootOFF();
		}
	}
	
	public static void main(String[] args) {
		// Check the comment at the top of the Camera class
		// for more information about this hack
		String os = System.getProperty("os.name"); 
		if (os != null && os.equals("Mac OS X")) {
			Camera.getDefault();
		}
		
		launch(args);
	}
}