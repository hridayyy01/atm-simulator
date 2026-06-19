package ASimulatorSystem;

import constants.CommonConstants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.*;

public class Signup2 extends JFrame {

    String formno;

    private JComboBox<String> creligion;
    private JComboBox<String> ccategory;
    private JComboBox<String> cincome;
    private JComboBox<String> ceducation;
    private JComboBox<String> coccupation;

    private JTextField tpan;
    private JTextField taadhar;

    private JRadioButton ryes;
    private JRadioButton rno;

    private ButtonGroup bg;
    private JButton nextButton;


    Signup2(String formno) {
        this.formno = formno;
        setTitle("Bank ATM - registration page 2");
        setSize(850,780);
        setLocationRelativeTo(null);
        setLayout(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        addGUIComponents();
    }

    private void addGUIComponents() {
        addDropDowns();
        addTextFields();
        addSeniorCitizensRadioButtons();
        addNextButton();
    }

    // helper
    private void addLabel(String text,int x,int y) {
        JLabel label = new JLabel(text);
        label.setBounds(x,y,CommonConstants.labelW2, CommonConstants.rowH);
        label.setFont(CommonConstants.font);
        add(label);
    }

    private void addDropDowns() {
        // -- religion label --
        addLabel("Religion : ",CommonConstants.labelX2,60);
        creligion = new JComboBox<>(new String[]{"Hindu","Muslim","Christian","Sikh","Others"});
        creligion.setBounds(CommonConstants.comboX2,60,CommonConstants.comboW2,CommonConstants.rowH);
        add(creligion);

        // -- category label --
        addLabel("Category : ",CommonConstants.labelX2,90);
        ccategory = new JComboBox<>(new String[]{"General","OBC","SC","ST"});
        ccategory.setBounds(CommonConstants.comboX2,90,CommonConstants.comboW2,CommonConstants.rowH);
        add(ccategory);

        //-- Income label --
        addLabel("Income : ",CommonConstants.labelX2,120);
        cincome = new JComboBox<>(new String[]{"< 1Lakh" , "1-5 lakhs" , "5-10 Lakhs" , " > 10 Lakh"});
        cincome.setBounds(CommonConstants.comboX2,120,CommonConstants.comboW2,CommonConstants.rowH);
        add(cincome);

        // -- Education Label --
        addLabel("Education : ",CommonConstants.labelX2,150);
        ceducation = new JComboBox<>(new String[]{"12th","Graduate","Post Graduate"});
        ceducation.setBounds(CommonConstants.comboX2,150,CommonConstants.comboW2,CommonConstants.rowH);
        add(ceducation);

        // -- occupation label --
        addLabel("Occupation:", CommonConstants.labelX2, 210);
        coccupation = new JComboBox<>(new String[]{"Business", "Service", "Agriculture", "Others"});
        coccupation.setBounds(CommonConstants.comboX2, 210, CommonConstants.comboW2, CommonConstants.rowH);
        add(coccupation);
    }

    private void addTextFields() {
        // -- pan --
        addLabel("Pan No : ",CommonConstants.labelX2,240);
        tpan = new JTextField();
        tpan.setBounds(CommonConstants.fieldX2,240,CommonConstants.fieldW2,CommonConstants.rowH);
        add(tpan);

        // -- aadhar --
        addLabel("Aadhar No : ",CommonConstants.labelX2,270);
        taadhar = new JTextField();
        taadhar.setBounds(CommonConstants.fieldX2,270,CommonConstants.fieldW2,CommonConstants.rowH);
        add(taadhar);
    }

    private void addSeniorCitizensRadioButtons() {
        addLabel("Senior Citizen : ",CommonConstants.labelX2,330);

        ryes = new JRadioButton("Yes");
        ryes.setFont(CommonConstants.radioButtonFont2);
        ryes.setBounds(CommonConstants.radioX2,330,CommonConstants.radioW2,CommonConstants.rowH);

        rno = new JRadioButton("No");
        rno.setFont(CommonConstants.radioButtonFont2);
        rno.setBounds(CommonConstants.radioX2 + CommonConstants.radioW2,330,CommonConstants.radioW2,CommonConstants.rowH);
        rno.setSelected(true);

        ButtonGroup bg = new ButtonGroup();
        bg.add(ryes);
        bg.add(rno);

        add(ryes);
        add(rno);
    }

    private void addNextButton() {
        nextButton = new JButton("Next");
        nextButton.setBounds(CommonConstants.r2);
        nextButton.setFont(CommonConstants.ButtonFont);
        nextButton.setForeground(Color.DARK_GRAY);
        nextButton.setOpaque(true);
        nextButton.setFocusPainted(false);
        add(nextButton);
        nextButton.addActionListener(new ActionListener() {
            private boolean isFormnoValid() {
                if(formno == null || formno.trim().isEmpty()) {
                    JOptionPane.showMessageDialog(Signup2.this,"Invalid form no please restart registration");
                    return false;
                }
                return true;
            }

            private void executeInsert() {
                PreparedStatement ps = null;
                Connection con = null;

                try {
                    Conn c1 = new Conn();
                    con = c1.c;

                    String religion = (String) creligion.getSelectedItem();
                    String category = (String) ccategory.getSelectedItem();
                    String income = (String) cincome.getSelectedItem();
                    String education = (String) ceducation.getSelectedItem();
                    String occupation = (String) coccupation.getSelectedItem();

                    String pan = tpan.getText().trim();
                    String aadhar = taadhar.getText().trim();

                    if(!aadhar.matches("\\d{12}")) {
                        JOptionPane.showMessageDialog(Signup2.this,"Aadhar must be exactly of 12 digits","Validation Error",JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    String senior = ryes.isSelected() ? "Yes" : "No";
                    String sql = "INSERT INTO signuptwo(formno,religion,category,income,education,occupation,pan,aadhar,isSenior)" + "VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    ps = con.prepareStatement(sql);
                    ps.setString(1,formno);
                    ps.setString(2,religion);
                    ps.setString(3,category);
                    ps.setString(4,income);
                    ps.setString(5,education);
                    ps.setString(6,occupation);
                    ps.setString(7,pan);
                    ps.setString(8,aadhar);
                    ps.setString(9,senior);
                    ps.executeUpdate();

                    JOptionPane.showMessageDialog(Signup2.this, "Details saved.");
                    new Signup3(formno).setVisible(true);
                    Signup2.this.dispose();
                } catch (SQLException e) {
                    JOptionPane.showMessageDialog(Signup2.this,"Database error : "+ e.getMessage(),"DB error",JOptionPane.ERROR_MESSAGE);
                    e.printStackTrace();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(Signup2.this,"Error : " + e.getMessage());
                } finally {
                  try {
                      if(ps != null) ps.close();
                      if(con != null) con.close();
                  } catch (SQLException e) {
                      e.printStackTrace();
                  }
                }
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                if(!isFormnoValid()) return;
                executeInsert();
            }
        });
    }
}
