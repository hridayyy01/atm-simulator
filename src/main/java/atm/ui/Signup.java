package atm.ui;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.model.PersonalDetails;
import atm.model.Registration;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class Signup extends JFrame {
    private final RegistrationDao registrationDao;
    private final AuthenticationDao authenticationDao;
    private final Registration registration;

    private JTextField nameField;
    private JTextField fatherNameField;
    private JTextField emailField;
    private JTextField addressField;
    private JTextField cityField;
    private JTextField pincodeField;
    private JTextField stateField;

    private JRadioButton maleRadio;
    private JRadioButton femaleRadio;

    private JRadioButton marriedRadio;
    private JRadioButton unmarriedRadio;
    private JRadioButton otherMaritalRadio;

    private JButton nextButton;

    public Signup(Registration registration, RegistrationDao registrationDao, AuthenticationDao authenticationDao) {
        this.registration = registration;
        this.registrationDao = registrationDao;
        this.authenticationDao = authenticationDao;
        setTitle("New Account - Application form");
        setSize(850, 780);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(null);
        getContentPane().setBackground(Color.WHITE);
        addGUIComponents();
    }

    private void addGUIComponents() {
        // -- logo --
        ImageIcon rawLogo = new ImageIcon(
                ClassLoader.getSystemResource("icons/logo.jpg")
        );
        Image scaledLogo = rawLogo.getImage().getScaledInstance(100, 100, Image.SCALE_SMOOTH);
        JLabel logoLabel = new JLabel(new ImageIcon(scaledLogo));
        logoLabel.setBounds(0, 0, 850, 100);
        logoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(logoLabel);

        // -- page heading --
        JLabel headingLabel = new JLabel("APPLICATION FORM NO: " + registration.getApplicationId());
        headingLabel.setFont(new Font("Raleway", Font.BOLD, 24));
        headingLabel.setBounds(0, 100, 850, 40);
        headingLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(headingLabel);

        JLabel subHeadingLabel = new JLabel("Page 1 of 3 - Personal Details");
        subHeadingLabel.setFont(new Font("Raleway", Font.PLAIN, 18));
        subHeadingLabel.setForeground(Color.GRAY);
        subHeadingLabel.setBounds(0, 140, 850, 30);
        subHeadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(subHeadingLabel);

        // -- labels --

        // -- name label --
        JLabel nameLabel = new JLabel("Full Name * ");
        nameLabel.setFont(CommonConstants.font);
        nameLabel.setBounds(CommonConstants.labelX, 200, CommonConstants.labelW, CommonConstants.rowH);
        add(nameLabel);
        nameField = new JTextField();
        nameField.setBounds(CommonConstants.fieldX, 200, CommonConstants.fieldW, CommonConstants.rowH);
        add(nameField);

        // -- fathers name --
        JLabel fatherNameLabel = new JLabel("Father's name * ");
        fatherNameLabel.setFont(CommonConstants.font);
        fatherNameLabel.setBounds(CommonConstants.labelX, 230, CommonConstants.labelW, CommonConstants.rowH);
        add(fatherNameLabel);
        fatherNameField = new JTextField();
        fatherNameField.setBounds(CommonConstants.fieldX, 230, CommonConstants.fieldW, CommonConstants.rowH);
        add(fatherNameField);

        // -- gender --
        JLabel genderLabel = new JLabel("Gender *");
        genderLabel.setFont(CommonConstants.font);
        genderLabel.setBounds(CommonConstants.labelX, 260, CommonConstants.labelW, CommonConstants.rowH);
        add(genderLabel);

        maleRadio = new JRadioButton("Male");
        maleRadio.setFont(CommonConstants.radioButtonFont);
        maleRadio.setBackground(Color.WHITE);
        maleRadio.setBounds(CommonConstants.fieldX, 260, 90, CommonConstants.rowH);
        add(maleRadio);

        femaleRadio = new JRadioButton("Female");
        femaleRadio.setFont(CommonConstants.radioButtonFont);
        femaleRadio.setBackground(Color.WHITE);
        femaleRadio.setBounds(CommonConstants.fieldX + 100, 260, 90, CommonConstants.rowH);
        add(femaleRadio);
        ButtonGroup genderGroup = new ButtonGroup();
        genderGroup.add(maleRadio);
        genderGroup.add(femaleRadio);

        // -- email --
        JLabel emailLabel = new JLabel("Email Address");
        emailLabel.setFont(CommonConstants.font);
        emailLabel.setBounds(CommonConstants.labelX, 290, CommonConstants.labelW, CommonConstants.rowH);
        add(emailLabel);
        emailField = new JTextField();
        emailField.setBounds(CommonConstants.fieldX, 290, CommonConstants.fieldW, CommonConstants.rowH);
        add(emailField);

        // -- marital status --
        JLabel maritalLabel = new JLabel("Marital Status * ");
        maritalLabel.setFont(CommonConstants.font);
        maritalLabel.setBounds(CommonConstants.labelX, 320, CommonConstants.labelW, CommonConstants.rowH);
        add(maritalLabel);

        marriedRadio = new JRadioButton("Married");
        unmarriedRadio = new JRadioButton("Unmarried");
        otherMaritalRadio = new JRadioButton("Other");
        for (JRadioButton rb : new JRadioButton[]{marriedRadio, unmarriedRadio, otherMaritalRadio}) {
            rb.setFont(CommonConstants.radioButtonFont);
            rb.setBackground(Color.WHITE);
        }

        marriedRadio.setBounds(CommonConstants.fieldX, 320, 90, CommonConstants.rowH);
        unmarriedRadio.setBounds(CommonConstants.fieldX + 100, 320, 150, CommonConstants.rowH);
        otherMaritalRadio.setBounds(CommonConstants.fieldX + 220, 320, 90, CommonConstants.rowH);

        add(marriedRadio);
        add(unmarriedRadio);
        add(otherMaritalRadio);

        ButtonGroup MaritalGroup = new ButtonGroup();
        MaritalGroup.add(marriedRadio);
        MaritalGroup.add(unmarriedRadio);
        MaritalGroup.add(otherMaritalRadio);

        // -- address --
        JLabel addresslabel = new JLabel("Address *");
        addresslabel.setFont(CommonConstants.font);
        addresslabel.setBounds(CommonConstants.labelX, 350, CommonConstants.labelW, CommonConstants.rowH);
        add(addresslabel);
        addressField = new JTextField();
        addressField.setBounds(CommonConstants.fieldX, 350, CommonConstants.fieldW, CommonConstants.rowH);
        add(addressField);

        // -- city --
        JLabel cityLabel = new JLabel("City *");
        cityLabel.setFont(CommonConstants.font);
        cityLabel.setBounds(CommonConstants.labelX, 380, CommonConstants.labelW, CommonConstants.rowH);
        add(cityLabel);
        cityField = new JTextField();
        cityField.setBounds(CommonConstants.fieldX, 380, CommonConstants.fieldW, CommonConstants.rowH);
        add(cityField);

        // -- pincode --
        JLabel pincodeLabel = new JLabel("Pincode *");
        pincodeLabel.setFont(CommonConstants.font);
        pincodeLabel.setBounds(CommonConstants.labelX, 410, CommonConstants.labelW, CommonConstants.rowH);
        add(pincodeLabel);
        pincodeField = new JTextField();
        pincodeField.setBounds(CommonConstants.fieldX, 410, CommonConstants.fieldW, CommonConstants.rowH);
        add(pincodeField);

        // -- State --
        JLabel stateLabel = new JLabel("State *");
        stateLabel.setFont(CommonConstants.font);
        stateLabel.setBounds(CommonConstants.labelX, 440, CommonConstants.labelW, CommonConstants.rowH);
        add(stateLabel);
        stateField = new JTextField();
        stateField.setBounds(CommonConstants.fieldX, 440, CommonConstants.fieldW, CommonConstants.rowH);
        add(stateField);

        // -- Required fields note
        JLabel requiredNote = new JLabel("* required Fields");
        requiredNote.setFont(CommonConstants.font);
        requiredNote.setForeground(Color.GRAY);
        requiredNote.setBounds(CommonConstants.labelX,480,CommonConstants.labelW,CommonConstants.rowH);
        add(requiredNote);

        // -- next button --
        nextButton = new JButton();
        nextButton.setText("Next");
        nextButton.setFont(CommonConstants.ButtonFont);
        nextButton.setForeground(Color.DARK_GRAY);
        nextButton.setOpaque(true);
        nextButton.setFocusPainted(false);
        nextButton.setBounds(CommonConstants.r);
        add(nextButton);
        nextButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent ae) {
                if (!nextButton.isEnabled()) return;
                String name = nameField.getText().trim();
                String fname = fatherNameField.getText().trim();
                String email = emailField.getText().trim();
                String address = addressField.getText().trim();
                String city = cityField.getText().trim();
                String pincode = pincodeField.getText().trim();
                String state = stateField.getText().trim();

                String gender = maleRadio.isSelected() ? "Male" : femaleRadio.isSelected() ? "Female" : "";
                String Marital = marriedRadio.isSelected() ? "Married" :
                                 unmarriedRadio.isSelected() ? "Unmarried" :
                                 otherMaritalRadio.isSelected() ? "Other" : "";

                if(name.isEmpty() || fname.isEmpty() || address.isEmpty() || city.isEmpty() || pincode.isEmpty() || state.isEmpty() || gender.isEmpty() || Marital.isEmpty()) {
                    JOptionPane.showMessageDialog(Signup.this,"Please fill all the required (fields marked with *).","Validation Error",JOptionPane.WARNING_MESSAGE);
                    return;
                }

                // validating pincode
                if(!pincode.matches("\\d{6}")) {
                    JOptionPane.showMessageDialog(Signup.this,"Pincode must be of exactly 6 digits","Validation Error",JOptionPane.WARNING_MESSAGE);
                    return;
                }

                PersonalDetails details = new PersonalDetails(name, fname, gender,
                        email, Marital, address, city, pincode, state);
                try {
                    nextButton.setEnabled(false);
                    registration.setPersonalDetails(details);
                    new Signup2(registration, registrationDao, authenticationDao).setVisible(true);
                    Signup.this.dispose();
                } catch (Exception e) {
                    nextButton.setEnabled(true);
                    JOptionPane.showMessageDialog(Signup.this,"Unable to continue. Please try again.");
                }
            }
        });
    }
}
