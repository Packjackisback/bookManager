import java.sql.*;
import java.util.Scanner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class BookShellApp {

  private static final String DB_URL = "jdbc:sqlite:books.db";
  private static final OkHttpClient client = new OkHttpClient();
  private static final String GOOGLE_BOOKS_API = "https://www.googleapis.com/books/v1/volumes?q=intitle:";
  private static boolean DEBUG = false;

  public static void main(String[] args) {
    if (System.getProperty("os.name") != "Linux" && System.getProperty("os.name") != "FreeBSD") {
      System.out
          .println("Due to the confidential nature of the contents, this program cannot run on a system with spyware.");
      return; // yeah fuck you windows users
    }
    if (args.length > 0 && args[0].equals("--debug")) {
      DEBUG = true;
    }
    try (Scanner scanner = new Scanner(System.in)) {
      setupDatabase();

      while (true) {
        System.out.println("\nOptions:");
        System.out.println("1. Add a book");
        System.out.println("2. View all books");
        System.out.println("3. Get a book by ISBN or name");
        System.out.println("4. Edit a book by id");
        System.out.println("5. Remove a book by id");
        System.out.println("6. Exit");
        System.out.print("Choose an option: ");
        int choice = scanner.nextInt();
        scanner.nextLine(); // Consume newline

        switch (choice) {
          case 1:
            addBook(scanner);
            break;
          case 2:
            viewBooks();
            break;
          case 3:
            System.out.print("Enter the ISBN or name of the book: ");
            String bookInput = scanner.nextLine();
            getBook(bookInput);
            break;
          case 4:
            System.out.print("Enter the ID of the book to edit: ");
            String idStr = scanner.nextLine();
            try {
              editBook(scanner, Integer.parseInt(idStr));
            } catch (NumberFormatException e) {
              System.out.println("Invalid ID. Please enter a valid number.");
              if (DEBUG) {
                e.printStackTrace();
              }
            }
            break;
          case 5:
            System.out.print("Enter the ID of the book to remove: ");
            removeBook(scanner.nextInt());
            scanner.nextLine(); // Consume newline
            break;
          case 6:
            System.out.println("Exiting...");
            return;
          default:
            System.out.println("Invalid choice. Please try again.");
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void setupDatabase() {
    try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
      String createTableSQL = "CREATE TABLE IF NOT EXISTS books (" + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
          + "name TEXT NOT NULL," + "isbn TEXT," + "area TEXT);";
      stmt.execute(createTableSQL);
    } catch (SQLException e) {
      System.err.println("Error setting up the database: " + e.getMessage());
    }
  }

  private static void addBook(Scanner scanner) {
    try (Connection conn = DriverManager.getConnection(DB_URL)) {
      System.out.print("Enter the name of the book: ");
      String name = scanner.nextLine();

      String isbn = fetchISBN(name);
      if (isbn == null) {
        System.out.println("ISBN not found for this book.");
      } else {
        System.out.println("Found ISBN: " + isbn);
      }

      System.out.print("Enter the area for the book: ");
      String area = scanner.nextLine();

      String insertSQL = "INSERT INTO books (name, isbn, area) VALUES (?, ?, ?);";
      try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
        pstmt.setString(1, name);
        pstmt.setString(2, isbn);
        pstmt.setString(3, area);
        pstmt.executeUpdate();
        System.out.println("Book added successfully.");
      }
    } catch (SQLException e) {
      System.err.println("Error adding the book: " + e.getMessage());
    }
  }

  private static String fetchISBN(String bookName) {
    String url = GOOGLE_BOOKS_API + bookName.replace(" ", "+");
    Request request = new Request.Builder().url(url).build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        System.err.println("Failed to fetch data: " + response.code());
        return null;
      }

      String responseData = response.body().string();
      JSONObject json = new JSONObject(responseData);
      JSONArray items = json.optJSONArray("items");

      if (items != null && items.length() > 0) {
        JSONObject volumeInfo = items.getJSONObject(0).getJSONObject("volumeInfo");
        JSONArray identifiers = volumeInfo.optJSONArray("industryIdentifiers");

        if (identifiers != null) {
          String isbn10 = null;
          String isbn13 = null;

          for (int i = 0; i < identifiers.length(); i++) {
            JSONObject identifier = identifiers.getJSONObject(i);
            String type = identifier.getString("type");
            String id = identifier.getString("identifier");

            if ("ISBN_13".equals(type)) {
              isbn13 = id;
            } else if ("ISBN_10".equals(type)) {
              isbn10 = id;
            }
          }

          // Prefer ISBN-13 if available
          return isbn13 != null ? isbn13 : isbn10;
        }
      }
    } catch (Exception e) {
      System.err.println("Error fetching ISBN: " + e.getMessage());
    }

    return null;
  }

  private static void viewBooks() {
    try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
      String query = "SELECT * FROM books;";
      try (ResultSet rs = stmt.executeQuery(query)) {
        while (rs.next()) {
          System.out.printf("ID: %d, Name: %s, ISBN: %s, Area: %s%n",
              rs.getInt("id"), rs.getString("name"), rs.getString("isbn"), rs.getString("area"));
        }
      }
    } catch (SQLException e) {
      System.err.println("Error fetching books: " + e.getMessage());
    }
  }

  private static void getBook(String bookInput) {
    String isbn = null;

    // Check if the input is a valid ISBN; if not, fetch the ISBN from the name
    if (bookInput.matches("^(97(8|9))?\\d{9}(\\d|X)$")) {
      // If it's a valid ISBN format
      isbn = bookInput;
    } else {
      // Attempt to fetch ISBN using the book name
      isbn = fetchISBN(bookInput);
      if (isbn == null) {
        System.out.println("No ISBN found for the book name provided.");
        return; // Exit the method if no ISBN is found
      }
    }

    // Now search for the book using the found or provided ISBN
    try (Connection conn = DriverManager.getConnection(DB_URL)) {
      String query = "SELECT * FROM books WHERE isbn = ?;";
      try (PreparedStatement pstmt = conn.prepareStatement(query)) {
        pstmt.setString(1, isbn);

        try (ResultSet rs = pstmt.executeQuery()) {
          if (rs.next()) {
            System.out.printf("ID: %d, Name: %s, ISBN: %s, Area: %s%n",
                rs.getInt("id"), rs.getString("name"), rs.getString("isbn"), rs.getString("area"));
          } else {
            System.out.println("No book found with the given ISBN.");
          }
        }
      }
    } catch (SQLException e) {
      System.err.println("Error retrieving book details: " + e.getMessage());
    }
  }

  private static void editBook(Scanner scanner, int id) {
    try (Connection conn = DriverManager.getConnection(DB_URL)) {
      String query = "SELECT * FROM books WHERE id = ?;";
      try (PreparedStatement pstmt = conn.prepareStatement(query)) {
        pstmt.setInt(1, id);
        try (ResultSet rs = pstmt.executeQuery()) {
          if (rs.next()) {
            // Book exists, prompt for new values
            System.out.println("Editing book:");
            System.out.printf("Current Name: %s%n", rs.getString("name"));
            System.out.printf("Current ISBN: %s%n", rs.getString("isbn"));
            System.out.printf("Current Area: %s%n", rs.getString("area"));
            System.out.print("Enter new name (leave empty to keep current): ");
            String newName = scanner.nextLine();
            if (newName.isEmpty()) {
              newName = rs.getString("name"); // Keep current name
            }

            String newIsbn = fetchISBN(newName); // Fetch new ISBN based on new name
            System.out.print("Enter new area (leave empty to keep current): ");
            String newArea = scanner.nextLine();
            if (newArea.isEmpty()) {
              newArea = rs.getString("area"); // Keep current area
            }

            String updateSQL = "UPDATE books SET name = ?, isbn = ?, area = ? WHERE id = ?;";
            try (PreparedStatement updatePstmt = conn.prepareStatement(updateSQL)) {
              updatePstmt.setString(1, newName);
              updatePstmt.setString(2, newIsbn);
              updatePstmt.setString(3, newArea);
              updatePstmt.setInt(4, id);
              updatePstmt.executeUpdate();
              System.out.println("Book information updated successfully.");
            }
          } else {
            System.out.println("No book found with the given ID.");
          }
        }
      }
    } catch (SQLException e) {
      System.err.println("Error editing book: " + e.getMessage());
    }
  }

  private static void removeBook(int id) {
    try (Connection conn = DriverManager.getConnection(DB_URL)) {
      String query = "DELETE FROM books WHERE id = ?;";
      try (PreparedStatement pstmt = conn.prepareStatement(query)) {
        pstmt.setInt(1, id);
        int rowsAffected = pstmt.executeUpdate();
        if (rowsAffected > 0) {
          System.out.println("Book removed successfully.");
        } else {
          System.out.println("No book found with the given ID.");
        }
      }
    } catch (SQLException e) {
      System.err.println("Error removing book: " + e.getMessage());
    }
  }
}
