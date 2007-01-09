package se.sics.mrm;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import org.apache.log4j.Logger;
import org.jdom.Element;

import se.sics.cooja.*;
import se.sics.cooja.interfaces.*;
import se.sics.mrm.MRM.*;

/**
 * The class AreaViewer belongs to the MRM package.
 * 
 * It is used to visualize available radios, traffic between them as well 
 * as the current radio propagation area of single radios.
 * Users may also add background images (such as maps) and color-analyze them
 * in order to add simulated obstacles in the radio medium.
 * 
 * For more information about MRM see MRM.java
 * 
 * @see MRM
 * @author Fredrik Osterlind
 */
@ClassDescription("MRM - Area Viewer")
@PluginType(PluginType.SIM_PLUGIN)
public class AreaViewer extends VisPlugin {
  private static final long serialVersionUID = 1L;
  private static Logger logger = Logger.getLogger(AreaViewer.class);
  
  private final JPanel canvas;
  private final VisPlugin thisPlugin;

  ChannelModel.TransmissionData dataTypeToVisualize = ChannelModel.TransmissionData.SIGNAL_STRENGTH;
  ButtonGroup visTypeSelectionGroup;
  
  // General drawing parameters
  private Point lastHandledPosition = new Point(0,0);
  private double zoomCenterX = 0.0;
  private double zoomCenterY = 0.0;
  private Point zoomCenterPoint = new Point();
  private double currentZoomX = 1.0f;
  private double currentZoomY = 1.0f;
  private double currentPanX = 0.0f;
  private double currentPanY = 0.0f;

  private boolean drawBackgroundImage = true;
  private boolean drawCalculatedObstacles = true;
  private boolean drawChannelProbabilities = true;
  private boolean drawRadios = true;
  private boolean drawRadioActivity = true;
  private boolean drawScaleArrow = true;

  // Background drawing parameters (meters)
  private double backgroundStartX = 0.0;
  private double backgroundStartY = 0.0;
  private double backgroundWidth = 0.0;
  private double backgroundHeight = 0.0;
  private Image backgroundImage = null;
  private File backgroundImageFile = null;

  // Obstacle drawing parameters (same scale as background)
  private boolean needToRepaintObstacleImage = false;
  private double obstacleStartX = 0.0;
  private double obstacleStartY = 0.0;
  private double obstacleWidth = 0.0;
  private double obstacleHeight = 0.0;
  private Image obstacleImage = null;
  
  // Channel probabilities drawing parameters (meters)
  private double channelStartX = 0.0;
  private double channelStartY = 0.0;
  private double channelWidth = 0.0;
  private double channelHeight = 0.0;
  private Image channelImage = null;

  private JSlider resolutionSlider;
  private JPanel controlPanel;
  private JScrollPane scrollControlPanel;
  
  private Simulation currentSimulation;
  private MRM currentRadioMedium;
  private ChannelModel currentChannelModel;
  
  private final String antennaImageFilename = "antenna.png";
  private final Image antennaImage;
  
  private Radio selectedRadio = null;
  private boolean inSelectMode = true;
  private boolean inTrackMode = false;
  
  private Vector<Line2D> trackedComponents = null;
  
  // Coloring variables
  private JPanel coloringIntervalPanel = null;
  private double coloringHighest = 0;
  private double coloringLowest = 0;
  private boolean coloringIsFixed = true;

  private Thread attenuatorThread = null;
  
  private JCheckBox showSettingsBox;
  private JCheckBox backgroundCheckBox;
  private JCheckBox obstaclesCheckBox;
  private JCheckBox channelCheckBox;
  private JCheckBox radiosCheckBox;
  private JCheckBox radioActivityCheckBox;
  private JCheckBox arrowCheckBox;
  
  /**
   * Initializes an AreaViewer.
   * 
   * @param simulationToVisualize Simulation using MRM
   */
  public AreaViewer(Simulation simulationToVisualize, GUI gui) {
    super("MRM - Area Viewer", gui);

    currentSimulation = simulationToVisualize;
    currentRadioMedium = (MRM) currentSimulation.getRadioMedium();
    currentChannelModel = currentRadioMedium.getChannelModel();
    
    // We want to listen to changes both in the channel model as well as in the radio medium
    currentChannelModel.addSettingsObserver(channelModelSettingsObserver);
    currentRadioMedium.addSettingsObserver(radioMediumSettingsObserver);
    currentRadioMedium.addRadioMediumObserver(radioMediumActivityObserver);
    
    // Set initial size etc.
    setSize(500, 500);
    setVisible(true);
    thisPlugin = this;
    
    // Canvas mode radio buttons + show settings checkbox
    showSettingsBox = new JCheckBox ("settings", true);
    showSettingsBox.setAlignmentY(Component.TOP_ALIGNMENT);
    showSettingsBox.setContentAreaFilled(false);
    showSettingsBox.setActionCommand("toggle show settings");
    showSettingsBox.addActionListener(canvasModeHandler);

    JRadioButton selectModeButton = new JRadioButton ("select");
    selectModeButton.setAlignmentY(Component.BOTTOM_ALIGNMENT);
    selectModeButton.setContentAreaFilled(false);
    selectModeButton.setActionCommand("set select mode");
    selectModeButton.addActionListener(canvasModeHandler);
    selectModeButton.setSelected(true);

    JRadioButton panModeButton = new JRadioButton ("pan");
    panModeButton.setAlignmentY(Component.BOTTOM_ALIGNMENT);
    panModeButton.setContentAreaFilled(false);
    panModeButton.setActionCommand("set pan mode");
    panModeButton.addActionListener(canvasModeHandler);

    JRadioButton zoomModeButton = new JRadioButton ("zoom");
    zoomModeButton.setAlignmentY(Component.BOTTOM_ALIGNMENT);
    zoomModeButton.setContentAreaFilled(false);
    zoomModeButton.setActionCommand("set zoom mode");
    zoomModeButton.addActionListener(canvasModeHandler);
    
    JRadioButton trackModeButton = new JRadioButton ("track rays");
    trackModeButton.setAlignmentY(Component.BOTTOM_ALIGNMENT);
    trackModeButton.setContentAreaFilled(false);
    trackModeButton.setActionCommand("set track rays mode");
    trackModeButton.addActionListener(canvasModeHandler);
    
    ButtonGroup group = new ButtonGroup();
    group.add(selectModeButton);
    group.add(panModeButton);
    group.add(zoomModeButton);
    group.add(trackModeButton);

    // Create canvas
    canvas = new JPanel() {
      private static final long serialVersionUID = 1L;
      public void paintComponent(Graphics g) {
        super.paintComponent(g);
        repaintCanvas((Graphics2D) g);
      }
    };
    canvas.setBorder(BorderFactory.createLineBorder(Color.BLACK));
    canvas.setBackground(Color.WHITE);
    canvas.setLayout(new BorderLayout());
    canvas.addMouseListener(canvasMouseHandler);
    
    // Create canvas mode panel
    JPanel canvasModePanel = new JPanel();
    canvasModePanel.setOpaque(false);
    canvasModePanel.setLayout(new BoxLayout(canvasModePanel, BoxLayout.Y_AXIS));
    canvasModePanel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
    canvasModePanel.add(showSettingsBox);
    canvasModePanel.add(Box.createVerticalGlue());
    canvasModePanel.add(selectModeButton);
    canvasModePanel.add(panModeButton);
    canvasModePanel.add(zoomModeButton);
    canvasModePanel.add(trackModeButton);
    canvas.add(BorderLayout.EAST, canvasModePanel);

    // Create control graphics panel
    JPanel graphicsComponentsPanel = new JPanel();
    graphicsComponentsPanel.setLayout(new BoxLayout(graphicsComponentsPanel, BoxLayout.Y_AXIS));
    graphicsComponentsPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    graphicsComponentsPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

    graphicsComponentsPanel.add(new JLabel("Show components:"));

    backgroundCheckBox = new JCheckBox("Background", true);
    backgroundCheckBox.setActionCommand("toggle background");
    backgroundCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(backgroundCheckBox);
    
    obstaclesCheckBox = new JCheckBox("Obstacles", true);
    obstaclesCheckBox.setActionCommand("toggle obstacles");
    obstaclesCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(obstaclesCheckBox);
    
    channelCheckBox = new JCheckBox("Channel probability", true);
    channelCheckBox.setActionCommand("toggle channel");
    channelCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(channelCheckBox);
    
    radiosCheckBox = new JCheckBox("Radios", true);
    radiosCheckBox.setActionCommand("toggle radios");
    radiosCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(radiosCheckBox);

    radioActivityCheckBox = new JCheckBox("Radio Activity", true);
    radioActivityCheckBox.setActionCommand("toggle radio activity");
    radioActivityCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(radioActivityCheckBox);

    arrowCheckBox = new JCheckBox("Scale arrow", true);
    arrowCheckBox.setActionCommand("toggle arrow");
    arrowCheckBox.addActionListener(selectGraphicsHandler);
    graphicsComponentsPanel.add(arrowCheckBox);

    graphicsComponentsPanel.add(Box.createRigidArea(new Dimension(0,20)));
    graphicsComponentsPanel.add(new JLabel("Configure attenuating obstacles:"));

    JButton addBackgroundButton = new JButton("Set background image");
    addBackgroundButton.setActionCommand("set background image");
    addBackgroundButton.addActionListener(setBackgroundHandler);
    graphicsComponentsPanel.add(addBackgroundButton);
    
    JButton analyzeObstaclesButton = new JButton("Analyze background for obstacles");
    analyzeObstaclesButton.setActionCommand("analyze for obstacles");
    analyzeObstaclesButton.addActionListener(analyzeObstaclesHandler);
    graphicsComponentsPanel.add(analyzeObstaclesButton);
    
    // Create visualize channel output panel
    JPanel visualizeChannelPanel = new JPanel();
    visualizeChannelPanel.setLayout(new BoxLayout(visualizeChannelPanel, BoxLayout.Y_AXIS));
    visualizeChannelPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    visualizeChannelPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

    // Channel coloring intervals
    visualizeChannelPanel.add(new JLabel("Color intervals:"));

    JRadioButton fixedColoringButton = new JRadioButton("Fixed channel coloring");
    fixedColoringButton.setSelected(true);
    fixedColoringButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        coloringIsFixed = true;
      }
    });
    visualizeChannelPanel.add(fixedColoringButton);

    JRadioButton relativeColoringButton = new JRadioButton("Relative channel coloring");
    relativeColoringButton.setSelected(true);
    relativeColoringButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        coloringIsFixed = false;
      }
    });
    visualizeChannelPanel.add(relativeColoringButton);

    ButtonGroup coloringGroup = new ButtonGroup();
    coloringGroup.add(fixedColoringButton);
    coloringGroup.add(relativeColoringButton);

    coloringIntervalPanel = new JPanel() {
      public void paintComponent(Graphics g) {
        super.paintComponent(g);

        int width = getWidth();
        int height = getHeight();
        double diff = coloringHighest - coloringLowest;
        int textHeight = g.getFontMetrics().getHeight();

        // If computing
        if (attenuatorThread != null && attenuatorThread.isAlive()) {
          g.setColor(Color.WHITE);
          g.fillRect(0, 0, width, height);
          g.setColor(Color.BLACK);
          String stringToDraw = "[calculating]";
          g.drawString(stringToDraw, width/2 - g.getFontMetrics().stringWidth(stringToDraw)/2, height/2 + textHeight/2);
          return;
        }
        
        // Check for infinite values
        if (Double.isInfinite(coloringHighest) || Double.isInfinite(coloringLowest)) {
          g.setColor(Color.WHITE);
          g.fillRect(0, 0, width, height);
          g.setColor(Color.BLACK);
          String stringToDraw = "INFINITE VALUES EXIST";
          g.drawString(stringToDraw, width/2 - g.getFontMetrics().stringWidth(stringToDraw)/2, height/2 + textHeight/2);
          return;
        }
        
        // Check if values are constant
        if (diff == 0) {
          g.setColor(Color.WHITE);
          g.fillRect(0, 0, width, height);
          g.setColor(Color.BLACK);
          NumberFormat formatter = DecimalFormat.getNumberInstance();
          String stringToDraw = "CONSTANT VALUES (" + formatter.format(coloringHighest) + ")";
          g.drawString(stringToDraw, width/2 - g.getFontMetrics().stringWidth(stringToDraw)/2, height/2 + textHeight/2);
          return;
        }

        for (int i=0; i < width; i++) {
          double paintValue = coloringLowest + (double) i / (double) width * diff;
          g.setColor(
              new Color(
                  getColorOfSignalStrength(paintValue, coloringLowest, coloringHighest)));
          
          g.drawLine(i, 0, i, height);
        }
        
        if (dataTypeToVisualize == ChannelModel.TransmissionData.PROB_OF_RECEPTION) {
          NumberFormat formatter = DecimalFormat.getPercentInstance();
          g.setColor(Color.BLACK);
          g.drawString(formatter.format(coloringLowest), 3, textHeight);
          String stringToDraw = formatter.format(coloringHighest);
          g.drawString(stringToDraw, width - g.getFontMetrics().stringWidth(stringToDraw) - 3, textHeight);
        } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH ||
            dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH_VAR ) {
          NumberFormat formatter = DecimalFormat.getNumberInstance();
          g.setColor(Color.BLACK);
          g.drawString(formatter.format(coloringLowest) + "dBm", 3, textHeight);
          String stringToDraw = formatter.format(coloringHighest) + "dBm";
          g.drawString(stringToDraw, width - g.getFontMetrics().stringWidth(stringToDraw) - 3, textHeight);
        } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SNR ||
            dataTypeToVisualize == ChannelModel.TransmissionData.SNR_VAR) {
          NumberFormat formatter = DecimalFormat.getNumberInstance();
          g.setColor(Color.BLACK);
          g.drawString(formatter.format(coloringLowest) + "dB", 3, textHeight);
          String stringToDraw = formatter.format(coloringHighest) + "dB";
          g.drawString(stringToDraw, width - g.getFontMetrics().stringWidth(stringToDraw) - 3, textHeight);
        } else if (dataTypeToVisualize == ChannelModel.TransmissionData.DELAY_SPREAD_RMS) {
          NumberFormat formatter = DecimalFormat.getNumberInstance();
          g.setColor(Color.BLACK);
          g.drawString(formatter.format(coloringLowest) + "us", 3, textHeight);
          String stringToDraw = formatter.format(coloringHighest) + "us";
          g.drawString(stringToDraw, width - g.getFontMetrics().stringWidth(stringToDraw) - 3, textHeight);
        }
        
      }
    };
    coloringIntervalPanel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
    Dimension colorPanelSize = new Dimension(200, 20);
    coloringIntervalPanel.setPreferredSize(colorPanelSize);
    coloringIntervalPanel.setMinimumSize(colorPanelSize);
    coloringIntervalPanel.setMaximumSize(colorPanelSize);
    coloringIntervalPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    visualizeChannelPanel.add(coloringIntervalPanel);

    // Choose channel output to visualize
    visualizeChannelPanel.add(Box.createRigidArea(new Dimension(0,20)));
    visualizeChannelPanel.add(new JLabel("Visualize radio output:"));

    JRadioButton signalStrengthButton = new JRadioButton("Signal strength");
    signalStrengthButton.setActionCommand("signalStrengthButton");
    signalStrengthButton.setSelected(true);
    signalStrengthButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.SIGNAL_STRENGTH;
      }
    });
    visualizeChannelPanel.add(signalStrengthButton);

    JRadioButton signalStrengthVarButton = new JRadioButton("Signal strength variance");
    signalStrengthVarButton.setActionCommand("signalStrengthVarButton");
    signalStrengthVarButton.setSelected(false);
    signalStrengthVarButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.SIGNAL_STRENGTH_VAR;
      }
    });
    visualizeChannelPanel.add(signalStrengthVarButton);
   
    JRadioButton SNRButton = new JRadioButton("Signal to Noise ratio");
    SNRButton.setActionCommand("SNRButton");
    SNRButton.setSelected(false);
    SNRButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.SNR;
      }
    });
    visualizeChannelPanel.add(SNRButton);
  
    JRadioButton SNRVarButton = new JRadioButton("Signal to Noise variance");
    SNRVarButton.setActionCommand("SNRVarButton");
    SNRVarButton.setSelected(false);
    SNRVarButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.SNR_VAR;
      }
    });
    visualizeChannelPanel.add(SNRVarButton);
   
    JRadioButton probabilityButton = new JRadioButton("Probability of reception");
    probabilityButton.setActionCommand("probabilityButton");
    probabilityButton.setSelected(false);
    probabilityButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.PROB_OF_RECEPTION;
      }
    });
    visualizeChannelPanel.add(probabilityButton);

    JRadioButton rmsDelaySpreadButton = new JRadioButton("RMS delay spread");
    rmsDelaySpreadButton.setActionCommand("rmsDelaySpreadButton");
    rmsDelaySpreadButton.setSelected(false);
    rmsDelaySpreadButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        dataTypeToVisualize = ChannelModel.TransmissionData.DELAY_SPREAD_RMS;
      }
    });
    visualizeChannelPanel.add(rmsDelaySpreadButton);
    
    visTypeSelectionGroup = new ButtonGroup();
    visTypeSelectionGroup.add(signalStrengthButton);
    visTypeSelectionGroup.add(signalStrengthVarButton);
    visTypeSelectionGroup.add(SNRButton);
    visTypeSelectionGroup.add(SNRVarButton);
    visTypeSelectionGroup.add(probabilityButton);
    visTypeSelectionGroup.add(rmsDelaySpreadButton);

    visualizeChannelPanel.add(Box.createRigidArea(new Dimension(0,20)));

    visualizeChannelPanel.add(new JLabel("Image resolution:"));
    
    resolutionSlider = new JSlider(JSlider.HORIZONTAL, 30, 600, 200);
    resolutionSlider.setMajorTickSpacing(100);
    resolutionSlider.setPaintTicks(true);
    resolutionSlider.setPaintLabels(true);
    visualizeChannelPanel.add(resolutionSlider);

    visualizeChannelPanel.add(Box.createRigidArea(new Dimension(0,20)));

    JButton recalculateVisibleButton = new JButton("Recalculate visible area");
    recalculateVisibleButton.setActionCommand("recalculate visible area");
    recalculateVisibleButton.addActionListener(formulaHandler);
    visualizeChannelPanel.add(recalculateVisibleButton);
    
    // Create control panel
    controlPanel = new JPanel();
    controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
    controlPanel.add(graphicsComponentsPanel);
    controlPanel.add(new JSeparator());
    controlPanel.add(Box.createRigidArea(new Dimension(0, 5)));
    controlPanel.add(visualizeChannelPanel);
    controlPanel.setPreferredSize(new Dimension(250,700));
    controlPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
    scrollControlPanel = new JScrollPane(
        controlPanel,
        JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, 
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scrollControlPanel.setPreferredSize(new Dimension(250,0));
    
    // Add everything
    this.setLayout(new BorderLayout());
    this.add(BorderLayout.CENTER, canvas); // Add canvas
    this.add(BorderLayout.EAST, scrollControlPanel);
       
    // Load external images (antenna)
    Toolkit toolkit = Toolkit.getDefaultToolkit();
    URL imageURL = this.getClass().getClassLoader().getResource(antennaImageFilename);
    antennaImage = toolkit.getImage(imageURL);
    
    MediaTracker tracker = new MediaTracker(canvas);
    tracker.addImage(antennaImage, 1);
    
    try {
      tracker.waitForAll();
    } catch (InterruptedException ex) {
      logger.fatal("Interrupted during image loading, aborting");
      return;
    }
    
    
    // Try to select current plugin
    try {
      setSelected(true);
    } catch (java.beans.PropertyVetoException e) {
      // Could not select
    }
  }
  
  /**
   * Listens to mouse event on canvas
   */
  private MouseListener canvasMouseHandler = new MouseListener() {
    public void mouseReleased(MouseEvent e) {
    }
    public void mouseExited(MouseEvent e) {
    }
    public void mouseClicked(MouseEvent e) {
      if (inSelectMode) {
        Vector<Radio> hitRadios = trackClickedRadio(e.getPoint());

        if (hitRadios == null || hitRadios.size() == 0) {
          if (e.getButton() != MouseEvent.BUTTON1) {
            selectedRadio = null;
            channelImage = null;
            canvas.repaint();
          }
          return;
        }

        if (hitRadios.size() == 1 && hitRadios.firstElement() == selectedRadio)
          return;
        
        if (selectedRadio == null || !hitRadios.contains(selectedRadio)) {
          selectedRadio = hitRadios.firstElement();
        } else {
          // Select next in list
          selectedRadio = hitRadios.get(
              (hitRadios.indexOf(selectedRadio)+1) % hitRadios.size()
          );
        }
       
        channelImage = null;
        canvas.repaint();
      } else if (inTrackMode && selectedRadio != null) {
        // Calculate real clicked position
        double realClickedX = e.getX() / currentZoomX - currentPanX; 
        double realClickedY = e.getY() / currentZoomY - currentPanY; 

        Position radioPosition = currentRadioMedium.getRadioPosition(selectedRadio);
        final double radioX = radioPosition.getXCoordinate();
        final double radioY = radioPosition.getYCoordinate();
        
        trackedComponents = currentChannelModel.getRaysOfTransmission(radioX, radioY, realClickedX, realClickedY);
        
        canvas.repaint();
      }
      
    }
    public void mouseEntered(MouseEvent e) {
    }
    public void mousePressed(MouseEvent e) {
      lastHandledPosition = new Point(e.getX(), e.getY());

      // Set zoom center (real world)
      zoomCenterX = e.getX() / currentZoomX - currentPanX; 
      zoomCenterY = e.getY() / currentZoomY - currentPanY; 
      zoomCenterPoint = e.getPoint();
    }
  };
  
  /**
   * Listens to mouse movements when in pan mode
   */
  private MouseMotionListener canvasPanModeHandler = new MouseMotionListener() {
    public void mouseMoved(MouseEvent e) {
    }
    public void mouseDragged(MouseEvent e) {
      if (lastHandledPosition == null) {
        lastHandledPosition = e.getPoint();
        return;
      }

      // Pan relative to mouse movement and current zoom
      // This way the mouse "lock" to the canvas
      currentPanX += ((float) (e.getX() - lastHandledPosition.x)) / currentZoomX;
      currentPanY += ((float) (e.getY() - lastHandledPosition.y)) / currentZoomY;
      lastHandledPosition = e.getPoint();
      
      canvas.repaint();
    }
  };
  
  /**
   * Listens to mouse movements when in zoom mode
   */
  private MouseMotionListener canvasZoomModeHandler = new MouseMotionListener() {
    public void mouseMoved(MouseEvent e) {
    }
    public void mouseDragged(MouseEvent e) {
      if (lastHandledPosition == null) {
        lastHandledPosition = e.getPoint();
        return;
      }

      // Zoom relative to mouse movement (keep XY-proportions)
      currentZoomY += 0.005 * currentZoomY * ((double) (lastHandledPosition.y - e.getY()));
      currentZoomY = Math.max(0.05, currentZoomY); 
      currentZoomY = Math.min(1500, currentZoomY); 
      currentZoomX = currentZoomY;

      // We also need to update the current pan in order to zoom towards the mouse
      currentPanX =  zoomCenterPoint.x/currentZoomX - zoomCenterX;
      currentPanY =  zoomCenterPoint.y/currentZoomY - zoomCenterY;
      
      lastHandledPosition = e.getPoint();
      canvas.repaint();
    }
  };
  
  /**
   * Selects which mouse mode the canvas should be in (select/pan/zoom)
   */
  private ActionListener canvasModeHandler = new ActionListener() {
    public void actionPerformed(ActionEvent e) {
      if (e.getActionCommand().equals("set select mode")) {
        // Select mode, no mouse motion listener needed
        for (MouseMotionListener reggedListener: canvas.getMouseMotionListeners())
          canvas.removeMouseMotionListener(reggedListener);

        inTrackMode = false;
        inSelectMode = true;
      } else if (e.getActionCommand().equals("set pan mode")) {
        // Remove all other mouse motion listeners
        for (MouseMotionListener reggedListener: canvas.getMouseMotionListeners())
          canvas.removeMouseMotionListener(reggedListener);
        inSelectMode = false;
        inTrackMode = false;
   
        // Add the special pan mouse motion listener
        canvas.addMouseMotionListener(canvasPanModeHandler);
      } else if (e.getActionCommand().equals("set zoom mode")) {
        // Remove all other mouse motion listeners
        for (MouseMotionListener reggedListener: canvas.getMouseMotionListeners())
          canvas.removeMouseMotionListener(reggedListener);
        inSelectMode = false;
        inTrackMode = false;
   
        // Add the special zoom mouse motion listener
        canvas.addMouseMotionListener(canvasZoomModeHandler);
      } else if (e.getActionCommand().equals("set track rays mode")) {
        // Remove all other mouse motion listeners
        for (MouseMotionListener reggedListener: canvas.getMouseMotionListeners())
          canvas.removeMouseMotionListener(reggedListener);
        inSelectMode = false;
        inTrackMode = true;
        
      } else if (e.getActionCommand().equals("toggle show settings")) {
        if (((JCheckBox) e.getSource()).isSelected())
          scrollControlPanel.setVisible(true);
          else
            scrollControlPanel.setVisible(false);
        thisPlugin.invalidate();
        thisPlugin.revalidate();

      }
    }
  };
  
  /**
   * Selects which graphical parts should be painted
   */
  private ActionListener selectGraphicsHandler = new ActionListener() {
    public void actionPerformed(ActionEvent e) {
      if (e.getActionCommand().equals("toggle background"))
        drawBackgroundImage = ((JCheckBox) e.getSource()).isSelected();
      else if (e.getActionCommand().equals("toggle obstacles"))
        drawCalculatedObstacles = ((JCheckBox) e.getSource()).isSelected();
      else if (e.getActionCommand().equals("toggle channel"))
        drawChannelProbabilities = ((JCheckBox) e.getSource()).isSelected();
      else if (e.getActionCommand().equals("toggle radios"))
        drawRadios = ((JCheckBox) e.getSource()).isSelected();
      else if (e.getActionCommand().equals("toggle radio activity"))
        drawRadioActivity = ((JCheckBox) e.getSource()).isSelected();
      else if (e.getActionCommand().equals("toggle arrow"))
        drawScaleArrow = ((JCheckBox) e.getSource()).isSelected();

      canvas.repaint();
    }
  };

  /**
   * Helps user set a background image which can be analysed for obstacles/freespace.
   */
  private ActionListener setBackgroundHandler = new ActionListener() {
    
    /**
     * Choosable file filter that supports tif, gif, jpg, png, bmp.
     */
    class ImageFilter extends FileFilter {
      public boolean accept(File f) {
        if (f.isDirectory()) {
          return true;
        }
        
        String filename = f.getName();
        if (filename != null) {
          if (filename.endsWith(".tiff") ||
              filename.endsWith(".tif") ||
              filename.endsWith(".gif") ||
              filename.endsWith(".jpg") ||
              filename.endsWith(".jpeg") ||
              filename.endsWith(".png") ||
              filename.endsWith(".bmp")) {
            return true;
          } 
        }
        return false;
      }
      
      public String getDescription() {
        return "All supported images";
      }
    }
    
    class ImageSettingsDialog extends JDialog {
      
      private double
      virtualStartX = 0.0,
      virtualStartY = 0.0,
      virtualWidth = 0.0,
      virtualHeight = 0.0;
      
      private JFormattedTextField
      virtualStartXField,
      virtualStartYField,
      virtualWidthField,
      virtualHeightField;

      private boolean terminatedOK = false;
      
      private NumberFormat doubleFormat = NumberFormat.getNumberInstance();

      /**
       * Creates a new dialog for settings background parameters
       */
      protected ImageSettingsDialog(File imageFile, Image image, Frame owner) {
        super(owner, "Image settings");
        
        JPanel tempPanel;
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        
        // Set layout and add components
        doubleFormat.setMinimumIntegerDigits(1);
        setLayout(new BoxLayout(this.getContentPane(), BoxLayout.Y_AXIS));

        tempPanel = new JPanel(new GridLayout(1, 2));
        tempPanel.add(new JLabel("Start X (m)     "));
        virtualStartXField = new JFormattedTextField(doubleFormat);
        virtualStartXField.setValue(new Double(0.0));
        tempPanel.add(virtualStartXField);
        add(tempPanel);

        tempPanel = new JPanel(new GridLayout(1, 2));
        tempPanel.add(new JLabel("Start Y (m)"));
        virtualStartYField = new JFormattedTextField(doubleFormat);
        virtualStartYField.setValue(new Double(0.0));
        tempPanel.add(virtualStartYField);
        add(tempPanel);

        tempPanel = new JPanel(new GridLayout(1, 2));
        tempPanel.add(new JLabel("Width (m)"));
        virtualWidthField = new JFormattedTextField(doubleFormat);
        virtualWidthField.setValue(new Double(100.0));
        tempPanel.add(virtualWidthField);
        add(tempPanel);

        tempPanel = new JPanel(new GridLayout(1, 2));
        tempPanel.add(new JLabel("Height (m)"));
        virtualHeightField = new JFormattedTextField(doubleFormat);
        virtualHeightField.setValue(new Double(100.0));
        tempPanel.add(virtualHeightField);
        add(tempPanel);

        add(Box.createVerticalGlue());
        add(Box.createVerticalStrut(10));
        
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(Box.createHorizontalGlue());

        final JButton okButton = new JButton("OK");
        this.getRootPane().setDefaultButton(okButton);
        final JButton cancelButton = new JButton("Cancel");
        okButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            virtualStartX = ((Number) virtualStartXField.getValue()).doubleValue();
            virtualStartY = ((Number) virtualStartYField.getValue()).doubleValue();
            virtualWidth = ((Number) virtualWidthField.getValue()).doubleValue();
            virtualHeight = ((Number) virtualHeightField.getValue()).doubleValue();

            terminatedOK = true;
            dispose();
}
        });
        cancelButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            terminatedOK = false;
            dispose();
          }
        });
        
        tempPanel.add(okButton);
        tempPanel.add(cancelButton);
        add(tempPanel);

        // Show dialog
        setModal(true);
        pack();
        setLocationRelativeTo(owner);
        setVisible(true);
      }

      public boolean terminatedOK() {
        return terminatedOK;
      }
      public double getVirtualStartX() {
        return virtualStartX;
      }
      public double getVirtualStartY() {
        return virtualStartY;
      }
      public double getVirtualWidth() {
        return virtualWidth;
      }
      public double getVirtualHeight() {
        return virtualHeight;
      }
    
    }

    public void actionPerformed(ActionEvent e) {
      if (e.getActionCommand().equals("set background image")) {
        
        // Let user select image file
        JFileChooser fileChooser = new JFileChooser();
        ImageFilter filter = new ImageFilter();
        fileChooser.addChoosableFileFilter(filter);
        
        int returnVal = fileChooser.showOpenDialog(canvas);
        
        if (returnVal != JFileChooser.APPROVE_OPTION) {
          // User cancelled
          return;
        } 
        
        File file = fileChooser.getSelectedFile();
        
        // User selected non-supported file, aborting
        if (!filter.accept(file)) {
          logger.fatal("Non-supported file type, aborting");
          return;
        }
        
        logger.info("Opening: " + file.getName() + ".");
        
        // Load image using toolkit and media tracker
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        Image image = toolkit.getImage(file.getAbsolutePath());
        
        MediaTracker tracker = new MediaTracker(canvas);
        tracker.addImage(image, 1);
        
        try {
          tracker.waitForAll();
          if (tracker.isErrorAny()) {
            logger.fatal("Error when loading image: ");
            for (Object errorObject: tracker.getErrorsAny())
              logger.fatal("> " + errorObject);
          }
          if (image == null) {
            logger.fatal("Image is null, aborting");
            return;
          }
        } catch (InterruptedException ex) {
          logger.fatal("Interrupted during image loading, aborting");
          return;
        }
        
        // Let user set virtual size of loaded image
        ImageSettingsDialog dialog = new ImageSettingsDialog(file, image, GUI.frame);
        
        if (!dialog.terminatedOK()) {
          logger.fatal("User cancelled, aborting");
          return;
        }

        // Add background image
        backgroundStartX = dialog.getVirtualStartX();
        backgroundStartY = dialog.getVirtualStartY();
        backgroundWidth = dialog.getVirtualWidth();
        backgroundHeight = dialog.getVirtualHeight();
        
        backgroundImage = image;
        backgroundImageFile = file;
      }
    }
  };

  /**
   * Helps user analyze a background for obstacles.
   */
  private ActionListener analyzeObstaclesHandler = new ActionListener() {
    
    class AnalyzeImageDialog extends JDialog {
      
      private NumberFormat intFormat = NumberFormat.getIntegerInstance();
      private BufferedImage imageToAnalyze = null;
      private BufferedImage obstacleImage = null;
      private JPanel canvasPanel = null;
      private boolean[][] obstacleArray = null;
      private boolean exitedOK = false;
      
      private JSlider redSlider, greenSlider, blueSlider, toleranceSlider, sizeSlider;
      
      /**
       * Listens to preview mouse motion event (when picking color)
       */
      private MouseMotionListener myMouseMotionListener = new MouseMotionListener() {
        public void mouseDragged(MouseEvent e) {
          
        }
        public void mouseMoved(MouseEvent e) {
          // Convert from mouse to image pixel position
          Point pixelPoint = new Point(
              (int) (e.getX() * (((double) imageToAnalyze.getWidth()) / ((double) canvasPanel.getWidth()))),
              (int) (e.getY() * (((double) imageToAnalyze.getHeight()) / ((double) canvasPanel.getHeight())))
          );
          
          // Fetch color
          int color = imageToAnalyze.getRGB(pixelPoint.x, pixelPoint.y);
          int red = (color & 0x00ff0000) >> 16;
          int green = (color & 0x0000ff00) >> 8;
          int blue = color & 0x000000ff;

          // Update sliders
          redSlider.setValue(red);
          redSlider.repaint();
          greenSlider.setValue(green);
          greenSlider.repaint();
          blueSlider.setValue(blue);
          blueSlider.repaint();
        }
      };
      
      /**
       * Listens to preview mouse event (when picking color)
       */
      private MouseListener myMouseListener = new MouseListener() {
        public void mouseClicked(MouseEvent e) {
          
        }
        public void mouseReleased(MouseEvent e) {
          
        }
        public void mouseEntered(MouseEvent e) {
          
        }
        public void mouseExited(MouseEvent e) {
          
        }
        public void mousePressed(MouseEvent e) {
          // Stop picking color again; remove mouse listeners and reset mouse cursor
          MouseListener[] allMouseListeners = canvasPanel.getMouseListeners();
          for (MouseListener mouseListener: allMouseListeners)
            canvasPanel.removeMouseListener(mouseListener);
          
          MouseMotionListener[] allMouseMotionListeners = canvasPanel.getMouseMotionListeners();
          for (MouseMotionListener mouseMotionListener: allMouseMotionListeners)
            canvasPanel.removeMouseMotionListener(mouseMotionListener);

          canvasPanel.setCursor(Cursor.getDefaultCursor());
        }
      };
      
      /**
       * Creates a new dialog for settings background parameters
       */
      protected AnalyzeImageDialog(Image currentImage, ChannelModel currentChannelModel, Frame owner) {
        super(owner, "Analyze for obstacles");
        
        JPanel tempPanel;
        JLabel tempLabel;
        JSlider tempSlider;
        JButton tempButton;
        
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Dimension labelDimension = new Dimension(100, 20);

        // Convert Image to BufferedImage
        imageToAnalyze = new BufferedImage(
            currentImage.getWidth(this), 
            currentImage.getHeight(this), 
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = imageToAnalyze.createGraphics();
        g.drawImage(currentImage, 0, 0, null);

        // Prepare initial obstacle image
        obstacleImage = new BufferedImage(
            currentImage.getWidth(this), 
            currentImage.getHeight(this), 
            BufferedImage.TYPE_INT_ARGB
        );
        
        // Set layout and add components
        intFormat.setMinimumIntegerDigits(1);
        intFormat.setMaximumIntegerDigits(3);
        intFormat.setParseIntegerOnly(true);
        setLayout(new BoxLayout(this.getContentPane(), BoxLayout.Y_AXIS));
        
        // Obstacle color
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Obstacle"));
        tempLabel.setPreferredSize(labelDimension);
        add(tempPanel);

        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Red"));
        tempLabel.setPreferredSize(labelDimension);
        tempLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        tempPanel.add(tempSlider = new JSlider(JSlider.HORIZONTAL, 0, 255, 0));
        tempSlider.setMajorTickSpacing(50);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        add(tempPanel);
        redSlider = tempSlider;

        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Green"));
        tempLabel.setPreferredSize(labelDimension);
        tempLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        tempPanel.add(tempSlider = new JSlider(JSlider.HORIZONTAL, 0, 255, 0));
        tempSlider.setMajorTickSpacing(50);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        add(tempPanel);
        greenSlider = tempSlider;

        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Blue"));
        tempLabel.setPreferredSize(labelDimension);
        tempLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        tempPanel.add(tempSlider = new JSlider(JSlider.HORIZONTAL, 0, 255, 0));
        tempSlider.setMajorTickSpacing(50);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        add(tempPanel);
        blueSlider = tempSlider;

        // Tolerance
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Tolerance"));
        tempLabel.setPreferredSize(labelDimension);
        tempLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        tempPanel.add(tempSlider = new JSlider(JSlider.HORIZONTAL, 0, 128, 0));
        tempSlider.setMajorTickSpacing(25);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        add(tempPanel);
        toleranceSlider = tempSlider;
        
        // Obstacle size
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(tempLabel = new JLabel("Obstacle size"));
        tempLabel.setPreferredSize(labelDimension);
        tempLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        tempPanel.add(tempSlider = new JSlider(JSlider.HORIZONTAL, 1, 40, 40));
        tempSlider.setInverted(true);
        tempSlider.setMajorTickSpacing(5);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        add(tempPanel);
        sizeSlider = tempSlider;
        
        // Buttons: Pick color, Preview obstacles etc.
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(Box.createHorizontalGlue());
        tempPanel.add(tempButton = new JButton("Pick color"));
        tempButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            // Set to color picker mode (if not already there)
            if (canvasPanel.getMouseMotionListeners().length == 0) {
              canvasPanel.addMouseListener(myMouseListener);
              canvasPanel.addMouseMotionListener(myMouseMotionListener);
              canvasPanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
          }
        });
        tempPanel.add(Box.createHorizontalStrut(5));
        tempPanel.add(tempButton = new JButton("Preview obstacles"));
        tempButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            obstacleImage = createObstacleImage();
            canvasPanel.repaint();
          }
        });
        add(tempPanel);

        add(Box.createVerticalStrut(10));

        // Preview image
        tempPanel = new JPanel() {
          public void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(imageToAnalyze, 0, 0, getWidth(), getHeight(), this);
            g.drawImage(obstacleImage, 0, 0, getWidth(), getHeight(), this);
          }
        };
        tempPanel.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.BLACK), "Preview"));
        tempPanel.setPreferredSize(new Dimension(400, 400));
        tempPanel.setBackground(Color.CYAN);
        add(tempPanel);
        canvasPanel = tempPanel; // Saved in canvasPanel
        
        // Buttons: Cancel, OK
        tempPanel = new JPanel();
        tempPanel.setLayout(new BoxLayout(tempPanel, BoxLayout.X_AXIS));
        tempPanel.add(Box.createHorizontalGlue());
        tempPanel.add(tempButton = new JButton("Cancel"));
        tempButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            dispose();
          }
        });
        tempPanel.add(Box.createHorizontalStrut(5));
        tempPanel.add(tempButton = new JButton("OK"));
        tempButton.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            obstacleImage = createObstacleImage();
            exitedOK = true;
            dispose();
          }
        });
        add(tempPanel);


        add(Box.createVerticalGlue());
        add(Box.createVerticalStrut(10));
        
        // Show dialog
        setModal(true);
        pack();
        setLocationRelativeTo(owner);
        setVisible(true);
      }

      /**
       * Create obstacle image by analyzing current background image
       * and using the current obstacle color, size and tolerance.
       * This method also creates the boolean array obstacleArray.
       * 
       * @return New obstacle image
       */
      private BufferedImage createObstacleImage() {
        int nrObstacles = 0;
        
        // Create new obstacle image all transparent (no obstacles)
        BufferedImage newObstacleImage = new BufferedImage(
            imageToAnalyze.getWidth(), 
            imageToAnalyze.getHeight(), 
            BufferedImage.TYPE_INT_ARGB
        );
        for (int x=0; x < imageToAnalyze.getWidth(); x++) {
          for (int y=0; y < imageToAnalyze.getHeight(); y++) {
            newObstacleImage.setRGB(x, y, 0x00000000);
          }
        }
        
        // Get target color to match against
        int targetRed = redSlider.getValue();
        int targetGreen = greenSlider.getValue();
        int targetBlue = blueSlider.getValue();

        // Get obstacle resolution and size
        int boxSize = sizeSlider.getValue();
        int tolerance = toleranceSlider.getValue();
        
        // Divide image into boxes and check each box for obstacles
        int arrayWidth = (int) Math.ceil((double) imageToAnalyze.getWidth() / (double) boxSize);
        int arrayHeight = (int) Math.ceil((double) imageToAnalyze.getHeight() / (double) boxSize);

        obstacleArray = new boolean[arrayWidth][arrayHeight];
        for (int x=0; x < imageToAnalyze.getWidth(); x+=boxSize) {
          for (int y=0; y < imageToAnalyze.getHeight(); y+=boxSize) {
            boolean boxIsObstacle = false;
            
            // Check all pixels in box for obstacles
            for (int xx=x; xx < x + boxSize && xx < imageToAnalyze.getWidth(); xx++) {
              for (int yy=y; yy < y + boxSize && yy < imageToAnalyze.getHeight(); yy++) {

                // Get current pixel color
                int color = imageToAnalyze.getRGB(xx, yy);
                int red = (color & 0x00ff0000) >> 16;
                int green = (color & 0x0000ff00) >> 8;
                int blue = color & 0x000000ff;
            
                // Calculate difference from target color
                int difference =
                  Math.abs(red - targetRed) + 
                  Math.abs(green - targetGreen) +
                  Math.abs(blue - targetBlue);
            
                // If difference is small enough make this box an obstacle
                if (difference <= tolerance) {
                  boxIsObstacle = true;
                  break;
                }
              }
              if (boxIsObstacle)
                break;
            }

            // If box is obstacle, colorize it
            if (boxIsObstacle) {
              obstacleArray[x/boxSize][y/boxSize] = true;
              nrObstacles++;

              // Colorize all pixels in the box
              for (int xx=x; xx < x + boxSize && xx < imageToAnalyze.getWidth(); xx++) {
                for (int yy=y; yy < y + boxSize && yy < imageToAnalyze.getHeight(); yy++) {
                  newObstacleImage.setRGB(xx, yy, 0x9922ff22);
                }
              }
            } else
              obstacleArray[x/boxSize][y/boxSize] = false;

            
          }
        } // End of "divide into boxes" for-loop
        
        return newObstacleImage;
      }

    }
    public void actionPerformed(ActionEvent e) {
      if (e.getActionCommand().equals("analyze for obstacles")) {
        if (backgroundImage == null)
          return;
        
        AnalyzeImageDialog analyzer = new AnalyzeImageDialog(
            backgroundImage, 
            currentChannelModel, 
            GUI.frame
        );
        
        // Check return value from analyzer
        if (analyzer.exitedOK) {
          // Remove old obstacle and register new
          final boolean[][] obstacleArray = analyzer.obstacleArray;

          final int boxSize = analyzer.sizeSlider.getValue();
          
          // Create progress monitor
          final ProgressMonitor pm = new ProgressMonitor(
              GUI.frame,
              "Registering obstacles",
              null,
              0,
              obstacleArray.length - 1
          );

          // Thread that will perform the work
          final Runnable runnable = new Runnable() { 
            public void run() { 
              try {
                int foundObstacles = 0;
                
                // Clear all old obstacles
                currentChannelModel.removeAllObstacles();

                for (int x=0; x < obstacleArray.length; x++) {
                  for (int y=0; y < (obstacleArray[0]).length; y++) {
                    // If obstacle, register it
                    if (obstacleArray[x][y]) {
                      double realWidth = ((double) boxSize * backgroundWidth) / (double) backgroundImage.getWidth(null);
                      double realHeight = ((double) boxSize * backgroundHeight) / (double) backgroundImage.getHeight(null);
                      double realStartX = backgroundStartX + (double) x * realWidth;
                      double realStartY = backgroundStartY + (double) y * realHeight;

                      foundObstacles++;
                      
                      if (realStartX + realWidth > backgroundStartX + backgroundWidth)
                        realWidth = backgroundStartX + backgroundWidth - realStartX;
                      if (realStartY + realHeight > backgroundStartY + backgroundHeight)
                        realHeight = backgroundStartY + backgroundHeight - realStartY;

                      currentChannelModel.addRectObstacle(
                          realStartX, 
                          realStartY, 
                          realWidth,
                          realHeight, 
                          false
                      );
                    }
                  }
                  // Check if the dialog has been cancelled
                  if (pm.isCanceled()) return;
                  
                  // Update progress
                  pm.setProgress(x);
                  pm.setNote("After/Before merging: " + 
                      currentChannelModel.getNumberOfObstacles() + 
                      "/" +
                      foundObstacles);
                }
                currentChannelModel.notifySettingsChanged();
                
                thisPlugin.repaint();
                
              } catch (Exception ex) {
                if (pm.isCanceled()) return;
                logger.fatal("Obstacle adding aborted..: ");
                ex.printStackTrace();
                pm.close();
              }
              pm.close();
            } 
          };
          
          // Start thread
          Thread thread = new Thread(runnable);
          thread.start();
        }
      }
    }
  };
    
  /**
   * Listens to settings changes in the radio medium.
   */
  private Observer radioMediumSettingsObserver = new Observer() {
    public void update(Observable obs, Object obj) {
      // Clear selected radio (if any selected) and radio medium coverage
      selectedRadio = null;
      channelImage = null;
      canvas.repaint();
    }
  };
  
  /**
   * Listens to settings changes in the radio medium.
   */
  private Observer radioMediumActivityObserver = new Observer() {
    public void update(Observable obs, Object obj) {
      // Just remove any selected radio (it may have been removed)
      canvas.repaint();
    }
  };
  
  /**
   * Listens to settings changes in the channel model.
   */
  private Observer channelModelSettingsObserver = new Observer() {
    public void update(Observable obs, Object obj) {
      needToRepaintObstacleImage = true;
      canvas.repaint();
    }
  };
  
  /**
   * Returns a color corresponding to given value where higher values are more green, and lower values are more red.
   * 
   * @param value Signal strength of received signal (dB)
   * @param lowBorder
   * @param highBorder
   * @return Integer containing standard ARGB color.
   */
  private int getColorOfSignalStrength(double value, double lowBorder, double highBorder) {
    double upperLimit = highBorder; // Best signal adds green
    double lowerLimit = lowBorder; // Bad signal adds red
    double intervalSize = (upperLimit - lowerLimit) / 2;
    double middleValue = lowerLimit + (upperLimit - lowerLimit) / 2;
    
    if (value > highBorder) {
      return 0xCC00FF00;
    }

    if (value < lowerLimit) {
      return 0xCCFF0000;
    }
    
    int red = 0, green = 0, blue = 0, alpha = 0xCC;
    
    // Upper limit (green)
    if (value > upperLimit - intervalSize) {
      green = (int) (255 - 255*(upperLimit - value)/intervalSize);
    }

    // Medium signal adds blue
    if (value > middleValue - intervalSize && value < middleValue + intervalSize) {
      blue = (int) (255 - 255*Math.abs(middleValue - value)/intervalSize);
    }

    // Bad signal adds red
    if (value < lowerLimit + intervalSize) {
      red = (int) (255 - 255*(value - lowerLimit)/intervalSize);
    }

    return (alpha << 24) | (red << 16) | (green << 8) | blue;
  }
  
  /**
   * Helps user adjust and calculate the channel propagation formula
   */
  private ActionListener formulaHandler = new ActionListener() {
    public void actionPerformed(ActionEvent e) {
      if (e.getActionCommand().equals("recalculate visible area")) {

        // Get resolution of new image
        final Dimension resolution = new Dimension(
            resolutionSlider.getValue(),
            resolutionSlider.getValue()
        );

        // Abort if no radio selected
        if (selectedRadio == null) {
          channelImage = null;
          canvas.repaint();
          return;
        }

        // Get new location/size of area to attenuate 
        final double startX = -currentPanX;
        final double startY = -currentPanY;
        final double width = canvas.getWidth() / currentZoomX;
        final double height = canvas.getHeight() / currentZoomY;

        // Get sending radio position
        Position radioPosition = currentRadioMedium.getRadioPosition(selectedRadio);
        final double radioX = radioPosition.getXCoordinate();
        final double radioY = radioPosition.getYCoordinate();

        // Create temporary image
        final BufferedImage tempChannelImage = new BufferedImage(resolution.width, resolution.height, BufferedImage.TYPE_INT_ARGB);
        
        // Save time for later analysis
        final long timeBeforeCalculating = System.currentTimeMillis();
        
        // Create progress monitor
        final ProgressMonitor pm = new ProgressMonitor(
            GUI.frame,
            "Calculating channel attenuation",
            null,
            0,
            resolution.width - 1
        );
        
        // Thread that will perform the work
        final Runnable runnable = new Runnable() { 
          public void run() { 
            try {

              // Available signal strength intervals
              double lowestImageValue = Double.MAX_VALUE;
              double highestImageValue = -Double.MAX_VALUE;
              
              // Create image values (calculate each pixel)
              double[][] imageValues = new double[resolution.width][resolution.height];
              for (int x=0; x < resolution.width; x++) {
                for (int y=0; y < resolution.height; y++) {

                  if (dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH) {
                    // Attenuate
                    double[] signalStrength = currentChannelModel.getReceivedSignalStrength(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height
                    );
                    
                    // Collecting signal strengths
                    if (signalStrength[0] < lowestImageValue)
                      lowestImageValue = signalStrength[0];
                    if (signalStrength[0] > highestImageValue)
                      highestImageValue = signalStrength[0];
                    
                    imageValues[x][y] = signalStrength[0];
                    
                  } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH_VAR) {
                    // Attenuate
                    double[] signalStrength = currentChannelModel.getReceivedSignalStrength(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height
                    );
                    
                    // Collecting variances
                    if (signalStrength[1] < lowestImageValue)
                      lowestImageValue = signalStrength[1];
                    if (signalStrength[1] > highestImageValue)
                      highestImageValue = signalStrength[1];
                    
                    imageValues[x][y] = signalStrength[1];
                    
                  } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SNR) {
                    // Get signal to noise ratio
                    double[] snr = currentChannelModel.getSINR(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height,
                        -Double.MAX_VALUE
                    );
                    
                    // Collecting signal to noise ratio
                    if (snr[0] < lowestImageValue)
                      lowestImageValue = snr[0];
                    if (snr[0] > highestImageValue)
                      highestImageValue = snr[0];
                    
                    imageValues[x][y] = snr[0];

                  } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SNR_VAR) {
                    // Get signal to noise ratio
                    double[] snr = currentChannelModel.getSINR(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height,
                        -Double.MAX_VALUE
                    );
                    
                    // Collecting variances
                    if (snr[1] < lowestImageValue)
                      lowestImageValue = snr[1];
                    if (snr[1] > highestImageValue)
                      highestImageValue = snr[1];
                    
                    imageValues[x][y] = snr[1];
                  } else if (dataTypeToVisualize == ChannelModel.TransmissionData.PROB_OF_RECEPTION) {
                    // Get probability of receiving a packet TODO What size? Does it matter?
                    double probability = currentChannelModel.getProbability(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height,
                        -Double.MAX_VALUE
                    )[0];
                    
                    // Collecting variances
                    if (probability < lowestImageValue)
                      lowestImageValue = probability;
                    if (probability > highestImageValue)
                      highestImageValue = probability;
                    
                    imageValues[x][y] = probability;
                  } else if (dataTypeToVisualize == ChannelModel.TransmissionData.DELAY_SPREAD_RMS) {
                    // Get RMS delay spread of receiving a packet
                    double rmsDelaySpread = currentChannelModel.getRMSDelaySpread(
                        radioX,
                        radioY,
                        startX + width * x/(double) resolution.width,
                        startY + height * y/(double) resolution.height
                    );
                    
                    // Collecting variances
                    if (rmsDelaySpread < lowestImageValue)
                      lowestImageValue = rmsDelaySpread;
                    if (rmsDelaySpread > highestImageValue)
                      highestImageValue = rmsDelaySpread;
                    
                    imageValues[x][y] = rmsDelaySpread;
                  }
                  
                  // Check if the dialog has been cancelled
                  if (pm.isCanceled()) return;
                  
                  // Update progress
                  pm.setProgress(x);
                  
                }
              }

              // Adjust coloring signal strength limit
              if (coloringIsFixed) {
                if (dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH) {
                  lowestImageValue = -100;
                  highestImageValue = 0;
                } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SIGNAL_STRENGTH_VAR) {
                  lowestImageValue = 0;
                  highestImageValue = 20;
                } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SNR) {
                  lowestImageValue = -10;
                  highestImageValue = 30;
                } else if (dataTypeToVisualize == ChannelModel.TransmissionData.SNR_VAR) {
                  lowestImageValue = 0;
                  highestImageValue = 20;
                } else if (dataTypeToVisualize == ChannelModel.TransmissionData.PROB_OF_RECEPTION) {
                  lowestImageValue = 0;
                  highestImageValue = 1;
                } else if (dataTypeToVisualize == ChannelModel.TransmissionData.DELAY_SPREAD_RMS) {
                  lowestImageValue = 0;
                  highestImageValue = 5;
                }
              }
              
              // Save coloring high-low interval
              coloringHighest = highestImageValue;
              coloringLowest = lowestImageValue;
              
              // Create image
              for (int x=0; x < resolution.width; x++) {
                for (int y=0; y < resolution.height; y++) {
                  
                  tempChannelImage.setRGB(
                      x, 
                      y, 
                      getColorOfSignalStrength(imageValues[x][y], lowestImageValue, highestImageValue)
                  );
                }
              }
              logger.info("Attenuating area done, time=" + (System.currentTimeMillis() - timeBeforeCalculating));
              
              // Repaint to show the new channel propagation
              channelStartX = startX;
              channelStartY = startY;
              channelWidth = width;
              channelHeight = height;
              channelImage = tempChannelImage;

              thisPlugin.repaint();
              coloringIntervalPanel.repaint();
              
            } catch (Exception ex) {
              if (pm.isCanceled()) return;
              logger.fatal("Attenuation aborted: " + ex);
              ex.printStackTrace();
              pm.close();
            }
            pm.close();
          } 
        };
        
        // Start thread
        attenuatorThread = new Thread(runnable);
        attenuatorThread.start();
      }
    }
  };

  /** 
   * Repaint the canvas
   * @param g2d Current graphics to paint on
   */
  protected void repaintCanvas(Graphics2D g2d) {
    AffineTransform originalTransform = g2d.getTransform();

    // Create "real-world" transformation (scaled 100 times to reduce double->int rounding errors)
    g2d.scale(currentZoomX, currentZoomY);
    g2d.translate(currentPanX, currentPanY);
    AffineTransform realWorldTransform = g2d.getTransform();
    g2d.scale(0.01, 0.01);
    AffineTransform realWorldTransformScaled = g2d.getTransform();
    
    // -- Draw background image if any --
    if (drawBackgroundImage && backgroundImage != null) {
      g2d.setTransform(realWorldTransformScaled);
      
      g2d.drawImage(backgroundImage,
          (int) ((double) backgroundStartX * 100.0),
          (int) ((double) backgroundStartY * 100.0),
          (int) ((double) backgroundWidth * 100.0),
          (int) ((double) backgroundHeight * 100.0),
          this);
    }
    
    // -- Draw calculated obstacles --
    if (drawCalculatedObstacles) {

      // (Re)create obstacle image if needed
      if (obstacleImage == null || needToRepaintObstacleImage) {

        // Abort if no obstacles exist
        if (currentChannelModel.getNumberOfObstacles() > 0) {

          // Get bounds of obstacles
          obstacleStartX = currentChannelModel.getObstacle(0).getMinX();
          obstacleStartY = currentChannelModel.getObstacle(0).getMinY();
          obstacleWidth = currentChannelModel.getObstacle(0).getMaxX();
          obstacleHeight = currentChannelModel.getObstacle(0).getMaxY();

          double tempVal = 0;
          for (int i=0; i < currentChannelModel.getNumberOfObstacles(); i++) {
            if ((tempVal = currentChannelModel.getObstacle(i).getMinX()) < obstacleStartX)
              obstacleStartX = tempVal;
            if ((tempVal = currentChannelModel.getObstacle(i).getMinY()) < obstacleStartY)
              obstacleStartY = tempVal;
            if ((tempVal = currentChannelModel.getObstacle(i).getMaxX()) > obstacleWidth)
              obstacleWidth = tempVal;
            if ((tempVal = currentChannelModel.getObstacle(i).getMaxY()) > obstacleHeight)
              obstacleHeight = tempVal;
          }
          obstacleWidth -= obstacleStartX;
          obstacleHeight -= obstacleStartY;

          // Create new obstacle image
          BufferedImage tempObstacleImage;
          if (backgroundImage != null)
            tempObstacleImage = new BufferedImage(
                Math.max(600, backgroundImage.getWidth(null)),
                Math.max(600, backgroundImage.getHeight(null)),
                BufferedImage.TYPE_INT_ARGB
            );
          else
            tempObstacleImage = new BufferedImage(
                600,
                600,
                BufferedImage.TYPE_INT_ARGB
            );
            
          Graphics2D obstacleGraphics = (Graphics2D) tempObstacleImage.getGraphics();

          // Set real world transform
          obstacleGraphics.scale(
              (double) tempObstacleImage.getWidth()/obstacleWidth,
              (double) tempObstacleImage.getHeight()/obstacleHeight
          );
          obstacleGraphics.translate(-obstacleStartX, -obstacleStartY);

          
          // Paint all obstacles
          obstacleGraphics.setColor(new Color(0, 0, 0, 128));

          // DEBUG: Use random obstacle color to distinguish different obstacles
          //Random random = new Random();
          
          for (int i=0; i < currentChannelModel.getNumberOfObstacles(); i++) {
            //obstacleGraphics.setColor((new Color(random.nextInt(255), random.nextInt(255), random.nextInt(255), 128)));
            obstacleGraphics.fill(currentChannelModel.getObstacle(i));
          }
          obstacleImage = tempObstacleImage;
 
        } else {

          // No obstacles exist - create dummy obstacle image
          obstacleStartX = 0;
          obstacleStartY = 0;
          obstacleWidth = 1;
          obstacleHeight = 1;
          obstacleImage = new BufferedImage(
              1,
              1,
              BufferedImage.TYPE_INT_ARGB
          );
        }
        
        needToRepaintObstacleImage = false;
      }
      
      // Painting in real world coordinates
      g2d.setTransform(realWorldTransformScaled);
      
      g2d.drawImage(obstacleImage,
          (int) ((double) obstacleStartX * 100.0),
          (int) ((double) obstacleStartY * 100.0),
          (int) ((double) obstacleWidth * 100.0),
          (int) ((double) obstacleHeight * 100.0),
          this); 
    }
    
    // -- Draw channel probabilities if calculated --
    if (drawChannelProbabilities && channelImage != null) {
      g2d.setTransform(realWorldTransformScaled);

      g2d.drawImage(channelImage,
          (int) ((double) channelStartX * 100.0),
          (int) ((double) channelStartY * 100.0),
          (int) ((double) channelWidth * 100.0),
          (int) ((double) channelHeight * 100.0),
          this); 
    }
    
    // -- Draw radios --
    if (drawRadios) {
      for (int i=0; i < currentRadioMedium.getRegisteredRadioCount(); i++) {
        g2d.setStroke(new BasicStroke((float) 0.0));
        g2d.setTransform(realWorldTransform);

        // Translate to real world radio position
        Radio radio = currentRadioMedium.getRegisteredRadio(i);
        Position radioPosition = currentRadioMedium.getRadioPosition(radio);
        g2d.translate(
            radioPosition.getXCoordinate(),
            radioPosition.getYCoordinate()
        );

        // Fetch current translation
        double xPos = g2d.getTransform().getTranslateX();
        double yPos = g2d.getTransform().getTranslateY();

        // Jump to identity transform and paint without scaling
        g2d.setTransform(new AffineTransform());

        if (selectedRadio == radio) {
          g2d.setColor(new Color(255, 0, 0, 100));
          g2d.fillRect(
              (int) xPos - antennaImage.getWidth(this)/2,
              (int) yPos - antennaImage.getHeight(this)/2,
              (int) antennaImage.getWidth(this),
              (int) antennaImage.getHeight(this)
          );
          g2d.setColor(Color.BLUE);
          g2d.drawRect(
              (int) xPos - antennaImage.getWidth(this)/2,
              (int) yPos - antennaImage.getHeight(this)/2,
              (int) antennaImage.getWidth(this),
              (int) antennaImage.getHeight(this)
          );
        }

        g2d.drawImage(antennaImage, (int) xPos - antennaImage.getWidth(this)/2, (int) yPos - antennaImage.getHeight(this)/2, this);

      } 
    }

    // -- Draw radio activity --
    if (drawRadioActivity) {
      // Paint scaled (otherwise bad rounding to integers may occur)
      g2d.setTransform(realWorldTransformScaled);
      g2d.setStroke(new BasicStroke((float) 0.0));

      for (RadioInterference interference: currentRadioMedium.getCurrentInterferencesArray()) {
        g2d.setColor(Color.RED);

        // Get source and destination coordinates
        Position sourcePosition = interference.mySource.source.position;
        Position destinationPosition = interference.myDestination.position;

        g2d.draw(new Line2D.Double(
            sourcePosition.getXCoordinate()*100.0,
            sourcePosition.getYCoordinate()*100.0,
            destinationPosition.getXCoordinate()*100.0,
            destinationPosition.getYCoordinate()*100.0
        ));
      } 
      for (RadioTransfer transfer: currentRadioMedium.getCurrentTransfersArray()) {
        g2d.setColor(Color.GREEN);

        // Get source and destination coordinates
        Position sourcePosition = transfer.mySource.source.position;
        Position destinationPosition = transfer.myDestination.position;

        g2d.draw(new Line2D.Double(
            sourcePosition.getXCoordinate()*100.0,
            sourcePosition.getYCoordinate()*100.0,
            destinationPosition.getXCoordinate()*100.0,
            destinationPosition.getYCoordinate()*100.0
        ));
      } 
      for (RadioTransmission transmission: currentRadioMedium.getCurrentTransmissionsArray()) {
        g2d.setColor(Color.BLUE);

        g2d.setTransform(realWorldTransform);
        Position sourcePosition = transmission.source.position;
       
        g2d.translate(
            sourcePosition.getXCoordinate(),
            sourcePosition.getYCoordinate()
        );

        // Fetch current translation
        double xPos = g2d.getTransform().getTranslateX();
        double yPos = g2d.getTransform().getTranslateY();

        // Jump to identity transform and paint without scaling
        g2d.setTransform(new AffineTransform());

        g2d.fillOval(
            (int) xPos,
            (int) yPos,
            (int) 5,
            (int) 5
        );

      
      } 
    }

    // -- Draw scale arrow --
    if (drawScaleArrow) {
      g2d.setStroke(new BasicStroke((float) .0));

      g2d.setColor(Color.BLACK);
      
      // Decide on scale comparator
      double currentArrowDistance = 0.1; // in meters
      if (currentZoomX < canvas.getWidth() / 2)
        currentArrowDistance = 0.1; // 0.1m
      if (currentZoomX < canvas.getWidth() / 2)
        currentArrowDistance = 1; // 1m
      if (10 * currentZoomX < canvas.getWidth() / 2)
        currentArrowDistance = 10; // 10m
      if (100 * currentZoomX < canvas.getWidth() / 2)
        currentArrowDistance = 100; // 100m
      if (1000 * currentZoomX < canvas.getWidth() / 2)
        currentArrowDistance = 1000; // 100m
      
      // "Arrow" points
      int pixelArrowLength = (int) (currentArrowDistance * currentZoomX);
      int xPoints[] = new int[] { -pixelArrowLength, -pixelArrowLength, -pixelArrowLength, 0, 0, 0 };   
      int yPoints[] = new int[] { -5, 5, 0, 0, -5, 5 };
      
      // Paint arrow and text
      g2d.setTransform(originalTransform);
      g2d.translate(canvas.getWidth() - 120, canvas.getHeight() - 20);
      g2d.drawString(currentArrowDistance + "m", -30, -10);
      g2d.drawPolyline(xPoints, yPoints, xPoints.length);
    }

    // -- Draw tracked components (if any) --
    if (inTrackMode && trackedComponents != null) {
      g2d.setTransform(realWorldTransformScaled);
      g2d.setStroke(new BasicStroke((float) 0.0));

      Random random = new Random();
      for (int i=0; i < trackedComponents.size(); i++) {
        g2d.setColor(new Color(255, random.nextInt(255), random.nextInt(255), 255));
        Line2D originalLine = trackedComponents.get(i);
        Line2D newLine = new Line2D.Double(
            originalLine.getX1()*100.0,
            originalLine.getY1()*100.0,
            originalLine.getX2()*100.0,
            originalLine.getY2()*100.0
        );
            
        g2d.draw(newLine);
      }
    }
    
    g2d.setTransform(originalTransform);
  }
  
  /**
   * Tracks an on-screen position and returns all hit radios.
   * May for example be used by a mouse listener to determine
   * if user clicked on a radio.
   * 
   * @param clickedPoint On-screen position
   * @return All hit radios
   */
  protected Vector<Radio> trackClickedRadio(Point clickedPoint) {
    Vector<Radio> hitRadios = new Vector<Radio>();
    if (currentRadioMedium.getRegisteredRadioCount() == 0)
      return null;

    double realIconHalfWidth = antennaImage.getWidth(this) / (currentZoomX*2.0); 
    double realIconHalfHeight = antennaImage.getHeight(this) / (currentZoomY*2.0); 
    double realClickedX = clickedPoint.x / currentZoomX - currentPanX; 
    double realClickedY = clickedPoint.y / currentZoomY - currentPanY; 

    for (int i=0; i < currentRadioMedium.getRegisteredRadioCount(); i++) {
      Radio testRadio = currentRadioMedium.getRegisteredRadio(i);
      Position testPosition = currentRadioMedium.getRadioPosition(testRadio);
      
      if (realClickedX > testPosition.getXCoordinate() - realIconHalfWidth &&
          realClickedX < testPosition.getXCoordinate() + realIconHalfWidth &&
          realClickedY > testPosition.getYCoordinate() - realIconHalfHeight &&
          realClickedY < testPosition.getYCoordinate() + realIconHalfHeight) {
        hitRadios.add(testRadio);
      }
    }

    if (hitRadios.size() == 0)
      return null;
    return hitRadios;
  }

  public void closePlugin() {
    // Remove all our observers

    if (currentChannelModel != null && channelModelSettingsObserver != null) {
      currentChannelModel.deleteSettingsObserver(channelModelSettingsObserver);
    } else {
      logger.fatal("Could not remove observer: " + channelModelSettingsObserver);
    }

    if (currentRadioMedium != null && radioMediumSettingsObserver != null) {
      currentRadioMedium.deleteSettingsObserver(radioMediumSettingsObserver);
    } else {
      logger.fatal("Could not remove observer: " + radioMediumSettingsObserver);
    }

    if (currentRadioMedium != null && radioMediumActivityObserver != null) {
      currentRadioMedium.deleteRadioMediumObserver(radioMediumActivityObserver);
    } else {
      logger.fatal("Could not remove observer: " + radioMediumActivityObserver);
    }
  }
  

  /**
   * Returns XML elements representing the current configuration.
   * 
   * @see #setConfigXML(Collection)
   * @return XML element collection
   */
  public Collection<Element> getConfigXML() {
    Vector<Element> config = new Vector<Element>();
    Element element;

    // Controls visible
    element = new Element("controls_visible");
    element.setText(Boolean.toString(showSettingsBox.isSelected()));
    config.add(element);

    // Viewport
    element = new Element("zoom_x");
    element.setText(Double.toString(currentZoomX));
    config.add(element);
    element = new Element("zoom_y");
    element.setText(Double.toString(currentZoomY));
    config.add(element);
    element = new Element("pan_x");
    element.setText(Double.toString(currentPanX));
    config.add(element);
    element = new Element("pan_y");
    element.setText(Double.toString(currentPanY));
    config.add(element);

    // Components shown
    element = new Element("show_background");
    element.setText(Boolean.toString(drawBackgroundImage));
    config.add(element);
    element = new Element("show_obstacles");
    element.setText(Boolean.toString(drawCalculatedObstacles));
    config.add(element);
    element = new Element("show_channel");
    element.setText(Boolean.toString(drawChannelProbabilities));
    config.add(element);
    element = new Element("show_radios");
    element.setText(Boolean.toString(drawRadios));
    config.add(element);
    element = new Element("show_activity");
    element.setText(Boolean.toString(drawRadioActivity));
    config.add(element);
    element = new Element("show_arrow");
    element.setText(Boolean.toString(drawScaleArrow));
    config.add(element);
    
    // Visualization type
    element = new Element("vis_type");
    element.setText(visTypeSelectionGroup.getSelection().getActionCommand());
    config.add(element);

    // Background image
    if (backgroundImageFile != null) {
      element = new Element("background_image");
      element.setText(backgroundImageFile.getPath());
      config.add(element);
      
      element = new Element("back_start_x");
      element.setText(Double.toString(backgroundStartX));
      config.add(element);
      element = new Element("back_start_y");
      element.setText(Double.toString(backgroundStartY));
      config.add(element);
      element = new Element("back_width");
      element.setText(Double.toString(backgroundWidth));
      config.add(element);
      element = new Element("back_height");
      element.setText(Double.toString(backgroundHeight));
      config.add(element);
    }

    // Resolution
    element = new Element("resolution");
    element.setText(Integer.toString(resolutionSlider.getValue()));
    config.add(element);

    return config;
  }

  /**
   * Sets the configuration depending on the given XML elements.
   * 
   * @see #getConfigXML()
   * @param configXML
   *          Config XML elements
   * @return True if config was set successfully, false otherwise
   */
  public boolean setConfigXML(Collection<Element> configXML) {
    for (Element element : configXML) {
      if (element.getName().equals("controls_visible")) {
        showSettingsBox.setSelected(Boolean.parseBoolean(element.getText()));
        canvasModeHandler.actionPerformed(new ActionEvent(showSettingsBox,
            ActionEvent.ACTION_PERFORMED, showSettingsBox.getActionCommand()));
      } else if (element.getName().equals("zoom_x")) {
        currentZoomX = Double.parseDouble(element.getText());
      } else if (element.getName().equals("zoom_y")) {
        currentZoomY = Double.parseDouble(element.getText());
      } else if (element.getName().equals("pan_x")) {
        currentPanX = Double.parseDouble(element.getText());
      } else if (element.getName().equals("pan_y")) {
        currentPanY = Double.parseDouble(element.getText());
      } else if (element.getName().equals("show_background")) {
        backgroundCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(backgroundCheckBox,
            ActionEvent.ACTION_PERFORMED, backgroundCheckBox.getActionCommand()));
      } else if (element.getName().equals("show_obstacles")) {
        obstaclesCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(obstaclesCheckBox,
            ActionEvent.ACTION_PERFORMED, obstaclesCheckBox.getActionCommand()));
      } else if (element.getName().equals("show_channel")) {
        channelCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(channelCheckBox,
            ActionEvent.ACTION_PERFORMED, channelCheckBox.getActionCommand()));
      } else if (element.getName().equals("show_radios")) {
        radiosCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(radiosCheckBox,
            ActionEvent.ACTION_PERFORMED, radiosCheckBox.getActionCommand()));
      } else if (element.getName().equals("show_activity")) {
        radioActivityCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(radioActivityCheckBox,
            ActionEvent.ACTION_PERFORMED, radioActivityCheckBox.getActionCommand()));
      } else if (element.getName().equals("show_arrow")) {
        arrowCheckBox.setSelected(Boolean.parseBoolean(element.getText()));
        selectGraphicsHandler.actionPerformed(new ActionEvent(arrowCheckBox,
            ActionEvent.ACTION_PERFORMED, arrowCheckBox.getActionCommand()));
      } else if (element.getName().equals("vis_type")) {
        String visTypeIdentifier = element.getText();
        Enumeration<AbstractButton> buttonEnum = visTypeSelectionGroup.getElements();
        while (buttonEnum.hasMoreElements()) {
          AbstractButton button = buttonEnum.nextElement();
          if (button.getActionCommand().equals(visTypeIdentifier)) {
            visTypeSelectionGroup.setSelected(button.getModel(), true);
            button.getActionListeners()[0]
                .actionPerformed(new ActionEvent(button,
                    ActionEvent.ACTION_PERFORMED, button.getActionCommand()));
          }
        }
      } else if (element.getName().equals("background_image")) {
        backgroundImageFile = new File(element.getText());
        if (backgroundImageFile.exists()) {
          Toolkit toolkit = Toolkit.getDefaultToolkit();
          backgroundImage = toolkit.getImage(backgroundImageFile.getAbsolutePath());
          
          MediaTracker tracker = new MediaTracker(canvas);
          tracker.addImage(backgroundImage, 1);

          try {
            tracker.waitForAll();
          } catch (InterruptedException ex) {
            logger.fatal("Interrupted during image loading, aborting");
            backgroundImage = null;
          }
          
        }
      } else if (element.getName().equals("back_start_x")) {
        backgroundStartX = Double.parseDouble(element.getText());
      } else if (element.getName().equals("back_start_y")) {
        backgroundStartY = Double.parseDouble(element.getText());
      } else if (element.getName().equals("back_width")) {
        backgroundWidth = Double.parseDouble(element.getText());
      } else if (element.getName().equals("back_height")) {
        backgroundHeight = Double.parseDouble(element.getText());
      } else if (element.getName().equals("resolution")) {
        resolutionSlider.setValue(Integer.parseInt(element.getText()));
      } else {
        logger.fatal("Unknown configuration value: " + element.getName());
      }
    }

    canvas.repaint();
    return true;    
  }


}
