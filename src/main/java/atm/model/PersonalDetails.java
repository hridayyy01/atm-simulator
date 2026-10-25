package atm.model;

public record PersonalDetails(
        String name,
        String fatherName,
        String gender,
        String email,
        String maritalStatus,
        String address,
        String city,
        String pincode,
        String state) {
}
