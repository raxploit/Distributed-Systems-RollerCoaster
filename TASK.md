# Group 2 - Roller Coaster Problem

There is a roller coaster with n wagons and m pleasure-seeking passengers. Each passenger wants to ride the roller coaster
repeatedly. Each wagon has seats for c passengers, c<m. A wagon only departs when all seats are occupied. After
completing the coaster (which takes a certain amount of time), all passengers disembark and look to ride again. The coaster
has only one track, so wagons cannot overtake each other. They always arrive in the same order they departed in.
Implement passengers and wagons as nodes that require sychronization.

- When a wagon arrives, c passengers should call a procedure i_am_boarding()
- A full wagon should call a procedure departure()
- After the end of the ride, the same c passengers should call a procedure i_am_disembarking()

## Comment

Start with n = 1, and extend the solution from there to multiple wagons. The solution should avoid starvation of passengers
(every waiting passenger is getting a ride) and should be fair (every passager is riding as often as possible). Highlight these aspects in your presentation. 
You can run more than one passenger node / wagon node per physical machine, but passengers and wagons should not be on
the same machines.