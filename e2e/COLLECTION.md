# Sujula — every endpoint, every caller

Run against a live server started under the `e2e` profile, which boots the whole
application on an in-memory database with the development seed already loaded:

    mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e

Then either import this file into Postman, or:

    cd e2e && npm install && npm test

**Run the folders in order.** Folder 00 captures the CSRF token that every write
outside the bearer-token surface needs, and folder 01 captures the access token
for each of the seeded people. Running a later folder alone sends an empty
`Authorization` header and everything in it is refused.

Every id, code, password and tracking number this collection sends comes out of
`src/main/resources/db/seed/dev-seed.sql`. Nothing is invented and nothing is
set up by the collection for its own benefit, so an assertion going red means
the application's answer changed rather than that a fixture drifted.
