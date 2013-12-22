public class Houses {
	
	static final double A = 1.1d;
	static final double B = 2.3d;
	static final double C = 3d;
	static final double D = 4.2d;
	static final double E = 5.1;
	static final double F = 6.3;
	
	static final double G = 13.4d;
	static final double H = 19.1d;
	static final double I = 27.5d;
	
	public static void main(String[] args) {
		double tokenIterator = 0d;
		double found = Double.MAX_VALUE;
		double tokenFound = 0d;
		
		while (tokenIterator < 1000d){
			double local = testF(tokenIterator);
			if (local < found){
				found = local;
				tokenFound = tokenIterator;
			}
			tokenIterator += 0.001d;
		}
		
		System.out.println("perfect place found: " + tokenFound);
		System.out.println("min(E^dist) found: " + found);
		
	}
	
	private static double testF(double token){
		double suma_odleglosci = 0d;
		suma_odleglosci += token < A ? (A - token) : (token - A);
		suma_odleglosci += token < B ? (B - token) : (token - B);
		suma_odleglosci += token < C ? (C - token) : (token - C);
		suma_odleglosci += token < D ? (D - token) : (token - D);
		suma_odleglosci += token < E ? (E - token) : (token - E);
		suma_odleglosci += token < F ? (F - token) : (token - F);
		suma_odleglosci += token < G ? (G - token) : (token - G);
		suma_odleglosci += token < H ? (H - token) : (token - H);
		suma_odleglosci += token < I ? (I - token) : (token - I);
		return suma_odleglosci;
	}

}