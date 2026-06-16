CREATE DATABASE IF NOT EXISTS bankdb;
USE bankdb;

CREATE TABLE IF NOT EXISTS signup (
    formno VARCHAR(20) PRIMARY KEY,
    name VARCHAR(20) NOT NULL,
    fname VARCHAR(20) NOT NULL,
    dob VARCHAR(20),
    gender VARCHAR(20),
    email VARCHAR(20),
    marital VARCHAR(20),
    address VARCHAR(80),
    city VARCHAR(20),
    pincode VARCHAR(10),
    state VARCHAR(30)
);

CREATE TABLE IF NOT EXISTS signuptwo (
    formno VARCHAR(20) PRIMARY KEY,
    religion VARCHAR(20),
    category VARCHAR(20),
    income VARCHAR(20),
    education VARCHAR(20),
    occupation VARCHAR(20),
    pan VARCHAR(20),
    aadhar VARCHAR(20),
    FOREIGN KEY (formno) REFERENCES signup(formno)
);

CREATE TABLE IF NOT EXISTS login (
    formno  VARCHAR(20),
    cardno VARCHAR(20)  PRIMARY KEY,
    pin  VARCHAR(10)  NOT NULL,
    FOREIGN KEY (formno) REFERENCES signup(formno)
);

CREATE TABLE IF NOT EXISTS bank (
    id INT AUTO_INCREMENT PRIMARY KEY,
    pin VARCHAR(10),
    date VARCHAR(30),
    type VARCHAR(30),
    amount VARCHAR(20)
);

